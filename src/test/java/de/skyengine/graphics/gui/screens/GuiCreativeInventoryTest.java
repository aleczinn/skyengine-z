package de.skyengine.graphics.gui.screens;

import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.item.CreativeTabs;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.gui.widget.TextField;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GuiCreativeInventoryTest {

    private GuiCreativeInventory screen;
    private TextField searchField;
    private float guiX;
    private float guiY;

    @BeforeAll
    static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @BeforeEach
    void openSearchTab() throws ReflectiveOperationException {
        this.screen = new GuiCreativeInventory(new SimpleItemStorage(36), null, null,
                () -> ItemStack.EMPTY);
        this.screen.init(null, 320, 240);
        this.screen.layout(320, 240);
        this.searchField = (TextField) field("searchField", TextField.class).get(this.screen);
        this.guiX = field("guiX", Float.TYPE).getFloat(this.screen);
        this.guiY = field("guiY", Float.TYPE).getFloat(this.screen);
    }

    @Test
    void itemListClickKeepsSearchFieldFocusedAndTypingReady() throws ReflectiveOperationException {
        this.searchField.text("oa");
        field(GuiContainer.class, "carried", ItemStack.class).set(this.screen,
                new ItemStack(CreativeTabs.all().getFirst(), 1));

        assertTrue(this.screen.mousePressed(null, this.guiX + 10, this.guiY + 19,
                GLFW.GLFW_MOUSE_BUTTON_LEFT));

        assertTrue(this.searchField.isFocused());
        assertTrue(this.screen.charTyped(null, 'k'));
        assertEquals("oak", this.searchField.getText());
    }

    @Test
    void scrollbarClickKeepsSearchFieldFocused() {
        this.searchField.text("stone");

        assertTrue(this.screen.mousePressed(null, this.guiX + 176, this.guiY + 20,
                GLFW.GLFW_MOUSE_BUTTON_LEFT));

        assertTrue(this.searchField.isFocused());
        assertTrue(this.screen.charTyped(null, 's'));
        assertEquals("stones", this.searchField.getText());
    }

    @Test
    void leftClickOnSearchTabKeepsTextButRightClickClearsIt() {
        this.searchField.text("oak");

        clickTopTab(0, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertFalse(this.searchField.visible);
        assertFalse(this.searchField.isFocused());

        clickTopTab(6, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertTrue(this.searchField.visible);
        assertTrue(this.searchField.isFocused());
        assertEquals("oak", this.searchField.getText());

        clickTopTab(6, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        assertTrue(this.searchField.isFocused());
        assertEquals("", this.searchField.getText());
    }

    @Test
    void rightClickInsideSearchFieldClearsWithoutLosingFocus() {
        this.searchField.text("oak");

        assertTrue(this.screen.mousePressed(null, this.guiX + 81, this.guiY + 5,
                GLFW.GLFW_MOUSE_BUTTON_RIGHT));

        assertEquals("", this.searchField.getText());
        assertTrue(this.searchField.isFocused());
        assertTrue(this.screen.charTyped(null, 's'));
        assertEquals("s", this.searchField.getText());
    }

    private void clickTopTab(int column, int button) {
        float x = column < 5 ? this.guiX + column * 27 : this.guiX + 195 - 27 * (7 - column) + 1;
        assertTrue(this.screen.mousePressed(null, x + 2, this.guiY - 26, button));
    }

    private static <T> Field field(String name, Class<T> type) throws ReflectiveOperationException {
        return field(GuiCreativeInventory.class, name, type);
    }

    private static <T> Field field(Class<?> owner, String name, Class<T> type)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        assertEquals(type, field.getType());
        field.setAccessible(true);
        return field;
    }
}
