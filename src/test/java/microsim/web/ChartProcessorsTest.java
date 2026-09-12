package microsim.web;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for JAS-mine Web chart processor support.
 * Verifies processor registry order and shared support behaviour such as reflection lookup,
 * field hierarchy traversal, series-shape checks, and layered-surface sentinels.
 *
 * @author ross richardson
 *
 */

public class ChartProcessorsTest {
    public static class MethodFixture {
        public int getXSize() { return 3; }
        public Object get(int x, int y) { return null; }
    }

    public static class ParentFieldFixture {
        private String inherited = "parent";
    }

    public static class ChildFieldFixture extends ParentFieldFixture {
        private int own = 7;
    }

    @BeforeEach
    public void setUp() {
        ChartProcessorSupport.clearCachesForTests();
    }

    @Test
    public void getProcessorsReturnsExpectedRegistryOrder() {
        List<String> names = ChartProcessors.getProcessors().stream()
            .map(p -> p.getClass().getSimpleName())
            .collect(Collectors.toList());

        assertEquals(List.of(
            "TimeSeriesProcessor",
            "BarProcessor",
            "HistogramProcessor",
            "ScatterProcessor",
            "PyramidProcessor",
            "LayeredSurfaceProcessor"
        ), names);
    }

    @Test
    public void getCachedMethodReturnsSameMethodInstanceForSameSignature() throws Exception {
        Method first = ChartProcessorSupport.getCachedMethod(MethodFixture.class, "getXSize");
        Method second = ChartProcessorSupport.getCachedMethod(MethodFixture.class, "getXSize");
        assertSame(first, second);
    }

    @Test
    public void getCachedMethodDistinguishesOverloadedSignatures() throws Exception {
        Method get = ChartProcessorSupport.getCachedMethod(MethodFixture.class, "get", int.class, int.class);
        assertEquals("get", get.getName());
        assertArrayEquals(new Class<?>[] { int.class, int.class }, get.getParameterTypes());
    }

    @Test
    public void fieldLookupFindsFieldsInClassHierarchy() throws Exception {
        Field inherited = ChartProcessorSupport.findFieldInHierarchy(ChildFieldFixture.class, "inherited");
        Field own = ChartProcessorSupport.findFieldInHierarchy(ChildFieldFixture.class, "own");

        assertEquals(ParentFieldFixture.class, inherited.getDeclaringClass());
        assertEquals(ChildFieldFixture.class, own.getDeclaringClass());
        assertNull(ChartProcessorSupport.findFieldInHierarchyOrNull(ChildFieldFixture.class, "missing"));
    }

    @Test
    public void requiredFieldLookupThrowsWhenMissing() throws Exception {
        assertThrows(NoSuchFieldException.class, () -> ChartProcessorSupport.findFieldInHierarchy(ChildFieldFixture.class, "missing"));
    }

    @Test
    public void seriesAllSameLengthAllowsLockstepSeriesOnly() {
        XYSeriesCollection lockstep = new XYSeriesCollection();
        XYSeries a = new XYSeries("a");
        a.add(1, 1);
        a.add(2, 2);
        XYSeries b = new XYSeries("b");
        b.add(1, 3);
        b.add(2, 4);
        lockstep.addSeries(a);
        lockstep.addSeries(b);
        assertTrue(ChartProcessorSupport.seriesAllSameLength(lockstep));

        XYSeriesCollection uneven = new XYSeriesCollection();
        XYSeries c = new XYSeries("c");
        c.add(1, 1);
        XYSeries d = new XYSeries("d");
        d.add(1, 2);
        d.add(2, 3);
        uneven.addSeries(c);
        uneven.addSeries(d);
        assertFalse(ChartProcessorSupport.seriesAllSameLength(uneven));
    }

    @Test
    public void gridEmptyColorSentinelCannotCollideWithDirectColorSentinels() {
        assertEquals(Integer.MIN_VALUE, ChartProcessorSupport.GRID_EMPTY_COLOR_VALUE);
        assertNotEquals(-1, ChartProcessorSupport.GRID_EMPTY_COLOR_VALUE);
        assertNotEquals(-2, ChartProcessorSupport.GRID_EMPTY_COLOR_VALUE);
    }
}
