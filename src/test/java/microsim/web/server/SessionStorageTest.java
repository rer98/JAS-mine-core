/* (C) Copyright 2026, by Ross Richardson
 *
 * Session Storage Test.
 *
 * @author ross richardson
 *
 */

package microsim.web.server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import microsim.data.StorageProtection;
import static org.junit.jupiter.api.Assertions.*;

class SessionStorageTest {
    @TempDir Path root;
    Path file(String name) throws Exception {
        Path p=root.resolve(name);Files.createDirectories(p.getParent());Files.writeString(p,"evidence");return p;
    }
    SessionStorage storage() {return new SessionStorage(root,100000,100,50);}
    String owner() {return UUID.randomUUID().toString();}
    @Test void admissionAndDisabledPolicy() throws Exception {
        file("input/a.xlsx");SessionStorage s=new SessionStorage(root,10,5,3);
        assertEquals(8L,s.status().get("usedBytes"));assertThrows(IllegalStateException.class,s::requireBuildCapacity);
        Files.delete(root.resolve("input/a.xlsx"));s.requireBuildCapacity();
        new SessionStorage(root,0,0,0).requireBuildCapacity();
    }
    private SessionStorage quota(long available) {
        long gib=1024L*1024L*1024L;
        return new SessionStorage(root,10*gib,3*gib,2*gib,true,()->new SessionStorage.Capacity(10*gib,available));
    }
    @Test void quotaCapacityUsesTheEnforcedFilesystemViewAndDoesNotInventAUsedCount() throws Exception {
        file("input/packaged.xlsx");long gib=1024L*1024L*1024L;
        var reading=quota(7*gib).status();
        assertEquals("docker-quota",reading.get("accounting"));
        assertEquals(7*gib,reading.get("remainingBytes"));
        assertEquals(true,reading.get("quotaCapacityVerified"));
        assertFalse(reading.containsKey("usedBytes"));
    }
    @Test void quotaReserveAndWarningUseStrict512MiBAndOneGiBBoundaries() throws Exception {
        long mib=1024L*1024L;
        assertThrows(IllegalStateException.class,()->quota(512*mib-1).requireBuildCapacity());
        quota(512*mib).requireBuildCapacity();
        assertEquals(true,quota(512*mib).status().get("warning"));
        assertEquals(true,quota(1024*mib-1).status().get("warning"));
        assertEquals(false,quota(1024*mib).status().get("warning"));
    }
    @Test void quotaModeRejectsHostWideOrInvalidCapacityInsteadOfFallingBackToFileCounts() {
        long gib=1024L*1024L*1024L;
        for(var capacity:List.of(new SessionStorage.Capacity(48*gib,7*gib),
                new SessionStorage.Capacity(10*gib,-1),new SessionStorage.Capacity(10*gib,11*gib))) {
            var storage=new SessionStorage(root,10*gib,3*gib,2*gib,true,()->capacity);
            assertThrows(java.io.IOException.class,storage::status);
            assertThrows(java.io.IOException.class,storage::requireBuildCapacity);
        }
    }
    @Test void selectedRunRetainsOnlyProtectedDatabaseFamiliesAndTheirPaths() throws Exception {
        for(String name:List.of("database/out.mv.db","database/out.lock.db","database/notes.txt",
                "input/input.mv.db","input/input.trace.db","input/options.txt","input/a.xlsx","csv/Person.csv")) file("output/first/"+name);
        file("output/second/input/input.mv.db");file("output/second/input/options.txt");
        file("output/current/input/input.mv.db");
        String out=owner(),pop=owner();
        try {
            StorageProtection.protectDatabase(out,root.resolve("output/first/database/out"),"Shared output");
            StorageProtection.protectDatabase(pop,root.resolve("output/first/input/input"),"Population reuse");
            SessionStorage s=storage();s.retire(root.resolve("output/first"));s.retire(root.resolve("output/second"));
            var preview=s.preview();assertTrue(preview.get("runs").toString().contains("Population reuse"));
            var result=s.delete((String)preview.get("token"),List.of("output/first","output/second"));
            assertEquals(true,result.get("complete"));
            for(String name:List.of("database/out.mv.db","database/out.lock.db","input/input.mv.db","input/input.trace.db"))
                assertTrue(Files.exists(root.resolve("output/first/"+name)),name);
            assertFalse(Files.exists(root.resolve("output/first/input/options.txt")));
            assertFalse(Files.exists(root.resolve("output/first/input/a.xlsx")));
            assertFalse(Files.exists(root.resolve("output/first/database/notes.txt")));
            assertFalse(Files.exists(root.resolve("output/first/csv")));
            assertFalse(Files.exists(root.resolve("output/second")));
            assertTrue(Files.exists(root.resolve("output/current/input/input.mv.db")));
            assertThrows(IllegalArgumentException.class,()->s.delete((String)preview.get("token"),List.of("output/first")));
        } finally {StorageProtection.release(out);StorageProtection.release(pop);}
    }
    @Test void independentOwnersMustBothReleaseBeforeDatabaseBecomesEligible() throws Exception {
        Path db=file("output/old/input/input.mv.db");String one=owner(),two=owner();
        try {
            StorageProtection.protectDatabase(one,db.resolveSibling("input"),"Reuse");
            StorageProtection.protectDatabase(two,db.resolveSibling("input"),"Connection");
            StorageProtection.release(one);assertEquals(List.of("Connection"),StorageProtection.reasons(db));
            SessionStorage s=storage();s.retire(root.resolve("output/old"));var p=s.preview();
            StorageProtection.release(two);
            assertThrows(IllegalArgumentException.class,()->s.delete((String)p.get("token"),List.of("output/old")));
            var next=s.preview();s.delete((String)next.get("token"),List.of("output/old"));assertFalse(Files.exists(db));
        } finally {StorageProtection.release(one);StorageProtection.release(two);}
    }
    @Test void changedFilesNewFilesAndLateProtectionRequireNewPreview() throws Exception {
        Path p=file("output/old/input/input.mv.db");SessionStorage s=storage();s.retire(root.resolve("output/old"));
        var first=s.preview();Files.writeString(p,"changed");
        assertThrows(IllegalArgumentException.class,()->s.delete((String)first.get("token"),List.of("output/old")));
        var next=s.preview();file("output/old/new.txt");
        assertThrows(IllegalArgumentException.class,()->s.delete((String)next.get("token"),List.of("output/old")));
        var last=s.preview();String owner=owner();
        try {StorageProtection.protectDatabase(owner,p.resolveSibling("input"),"New connection");
            assertThrows(IllegalArgumentException.class,()->s.delete((String)last.get("token"),List.of("output/old")));
        } finally {StorageProtection.release(owner);}
        assertTrue(Files.exists(p));
    }
    @Test void symlinksAndUnlistedRunsCannotBeDeleted() throws Exception {
        Path p=file("input/keep.csv");file("output/old/csv/A.csv");
        Files.createSymbolicLink(root.resolve("output/old/link.csv"),p);
        SessionStorage s=storage();s.retire(root.resolve("output/old"));var preview=s.preview();
        assertTrue(preview.get("runs").toString().contains("Symbolic link"));
        assertThrows(IllegalArgumentException.class,()->s.delete((String)preview.get("token"),List.of("../input")));
        var next=s.preview();s.delete((String)next.get("token"),List.of("output/old"));
        assertTrue(Files.exists(p));assertTrue(Files.isSymbolicLink(root.resolve("output/old/link.csv")));
    }
}
