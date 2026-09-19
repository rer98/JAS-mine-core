/* (C) Copyright 2026, by Ross Richardson
 *
 * Gui Utils Web Mode Test.
 *
 * @author ross richardson
 *
 */

package microsim.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.swing.JInternalFrame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GuiUtilsWebModeTest {

    @BeforeEach
    void setUp() {
        GuiUtils.clearRegistry();
        GuiUtils.setWebMode(true);
    }

    @AfterEach
    void tearDown() {
        GuiUtils.clearRegistry();
        GuiUtils.setWebMode(false);
    }

    @Test
    void webModeCanBeToggled() {
        assertTrue(GuiUtils.isWebMode());

        GuiUtils.setWebMode(false);

        assertFalse(GuiUtils.isWebMode());
    }

    @Test
    void addWindowRegistersInternalFrameInWebMode() {
        JInternalFrame frame = new JInternalFrame("chart");

        GuiUtils.addWindow(frame);

        assertEquals(List.of(frame), GuiUtils.getPlotterRegistry());
    }

    @Test
    void positionedAddWindowRegistersInternalFrameInWebMode() {
        JInternalFrame frame = new JInternalFrame("positioned chart");

        GuiUtils.addWindow(frame, 10, 20, 300, 200);

        assertEquals(List.of(frame), GuiUtils.getPlotterRegistry());
    }

    @Test
    void registryAccessReturnsImmutableSnapshot() {
        JInternalFrame frame = new JInternalFrame("chart");
        GuiUtils.addWindow(frame);

        List<JInternalFrame> snapshot = GuiUtils.getPlotterRegistry();
        GuiUtils.clearRegistry();

        assertEquals(List.of(frame), snapshot);
        assertTrue(GuiUtils.getPlotterRegistry().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new JInternalFrame("other")));
    }

    @Test
    void openProbeIsSuppressedInWebMode() {
        assertNull(GuiUtils.openProbe(new Object(), "probe"));
    }
}
