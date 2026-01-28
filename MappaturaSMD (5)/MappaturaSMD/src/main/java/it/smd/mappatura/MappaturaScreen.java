package it.smd.mappatura;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class MappaturaScreen extends Screen {

    private final MappingController controller;
    private TextFieldWidget sessionField;

    public MappaturaScreen(MappingController controller) {
        super(Text.literal("Mappatura SMD"));
        this.controller = controller;
    }

    @Override
    protected void init() {
        AppConfig cfg = ConfigManager.get();

        int w = this.width;
        int h = this.height;
        int fieldW = Math.min(360, w - 40);
        int x = (w - fieldW) / 2;
        int y = h / 4;

        sessionField = new TextFieldWidget(this.textRenderer, x, y + 30, fieldW, 20, Text.literal(""));
        sessionField.setPlaceholder(Text.literal("SMD-ZR-YYYYMMDD-XXXXX"));
        sessionField.setText(cfg.sessionCode == null ? "" : cfg.sessionCode);
        addDrawableChild(sessionField);

        ButtonWidget autoStartBtn = ButtonWidget.builder(
                Text.literal(cfg.autoStart ? "Auto-start: ON" : "Auto-start: OFF")
                        .formatted(cfg.autoStart ? Formatting.GREEN : Formatting.GRAY),
                btn -> {
                    cfg.autoStart = !cfg.autoStart;
                    ConfigManager.save();
                    btn.setMessage(Text.literal(cfg.autoStart ? "Auto-start: ON" : "Auto-start: OFF")
                            .formatted(cfg.autoStart ? Formatting.GREEN : Formatting.GRAY));
                }).dimensions(x, y + 60, fieldW, 20).build();
        addDrawableChild(autoStartBtn);

        ButtonWidget saveTestBtn = ButtonWidget.builder(
                Text.literal("Salva + Test"),
                btn -> {
                    cfg.sessionCode = sessionField.getText().trim();
                    ConfigManager.save();
                    SubmitPlotClient.checkAccessAsync(cfg.sessionCode, ar -> {
                        MinecraftClient.getInstance().execute(() -> {
                            if (ar.authorized) {
                                HudOverlay.show(Text.literal("⟡ COLLEGATO").formatted(Formatting.GREEN));
                            } else {
                                HudOverlay.show(Text.literal("⟡ " + ar.reason).formatted(Formatting.RED));
                            }
                        });
                    });
                }).dimensions(x, y + 90, fieldW, 20).build();
        addDrawableChild(saveTestBtn);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Mappatura SMD").formatted(Formatting.WHITE),
                width / 2, height / 4 - 10, 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
