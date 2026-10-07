package com.example.addon.hud;

import com.example.addon.AddonTemplate;
import com.mojang.blaze3d.platform.NativeImage;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ImageHud extends HudElement {
    public static final HudElementInfo<ImageHud> INFO = new HudElementInfo<>(
        AddonTemplate.HUD_GROUP,
        "custom-image",
        "Displays a custom image on your HUD.",
        ImageHud::new
    );

    private static final File FOLDER = new File(MeteorClient.FOLDER, "hud-images");
    private static int counter = 0;

    private static final String[] EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".gif"};

    private static final String DEFAULT_IMAGE = "Ninjajuulhead.png";

    private static final int MAX_SIZE = 512;
    private static final int GIF_MAX_SIZE = 256;
    private static final int MAX_FRAMES = 200;

    static {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ImageHud.class.getClassLoader());
        try {
            ImageIO.scanForPlugins();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private final SettingGroup sgImage = settings.createGroup("Image");
    private final SettingGroup sgSize = settings.createGroup("Size");

    private final Setting<String> file = sgImage.add(new ProvidedStringSetting.Builder()
        .name("image")
        .description("Pick one of the images in your hud-images folder.")
        .defaultValue(DEFAULT_IMAGE)
        .supplier(ImageHud::listImages)
        .build()
    );

    private final Setting<Boolean> choose = sgImage.add(new BoolSetting.Builder()
        .name("choose-image")
        .description("Toggle on to upload an image (png, jpg, webp, gif) from your computer.")
        .defaultValue(false)
        .onChanged(this::onChoosePressed)
        .build()
    );

    private final Setting<SettingColor> tint = sgImage.add(new ColorSetting.Builder()
        .name("tint")
        .description("Color multiplier. White leaves the image unchanged; lower alpha makes it transparent.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );


    private final Setting<Boolean> custom = sgSize.add(new BoolSetting.Builder()
        .name("custom-size")
        .description("Set an exact width/height instead of using a scale multiplier.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgSize.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size multiplier of the image.")
        .defaultValue(1)
        .min(0.05)
        .sliderRange(0.1, 5)
        .visible(() -> !custom.get())
        .build()
    );

    private final Setting<Integer> width = sgSize.add(new IntSetting.Builder()
        .name("width")
        .description("Width in pixels.")
        .defaultValue(100)
        .min(1)
        .sliderRange(1, 1000)
        .visible(custom::get)
        .build()
    );

    private final Setting<Integer> height = sgSize.add(new IntSetting.Builder()
        .name("height")
        .description("Height in pixels.")
        .defaultValue(100)
        .min(1)
        .sliderRange(1, 1000)
        .visible(custom::get)
        .build()
    );

    private final int instance = counter++;

    private byte[][] frames = null;      
    private int[] delays = null;         
    private Identifier[] ids = null;
    private DynamicTexture[] textures = null;
    private int frame = 0;
    private long frameStart = 0;

    private String loadedName = null;
    private int imgW, imgH;
    private int loadToken = 0;
    private boolean loading = false;
    private volatile boolean dialogOpen = false;

    private record Decoded(List<byte[]> frames, List<Integer> delays) {}

    public ImageHud() {
        super(INFO);
        FOLDER.mkdirs();
        extractDefaultImage();
    }

    private static void extractDefaultImage() {
        File target = new File(FOLDER, DEFAULT_IMAGE);
        if (target.exists()) return;

        try (InputStream in = ImageHud.class.getResourceAsStream("/" + DEFAULT_IMAGE)) {
            if (in == null) {
                AddonTemplate.LOG.warn("Bundled default image is missing from the jar");
                return;
            }
            Files.copy(in, target.toPath());
        } catch (IOException e) {
            AddonTemplate.LOG.error("Could not extract default image", e);
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        if (!file.get().equals(loadedName)) load();

        if (frames == null) {
            setSize(100, 40);
            if (isInEditor()) {
                renderer.quad(x, y, getWidth(), getHeight(), new Color(0, 0, 0, 120));
                renderer.text(loading ? "Loading..." : "No image", x + 4, y + 4, loading ? Color.WHITE : Color.RED, true);
                renderer.text("Upload via settings", x + 4, y + 16, Color.GRAY, true);
            }
            return;
        }

        advanceFrame();
        if (!ensureTexture(frame)) return;

        double w, h;
        if (custom.get()) {
            w = width.get();
            h = height.get();
        } else {
            w = imgW * scale.get();
            h = imgH * scale.get();
        }

        setSize(w, h);
        renderer.texture(ids[frame], x, y, w, h, tint.get());
    }


    private void advanceFrame() {
        if (frames.length <= 1) return;

        long now = System.currentTimeMillis();
        if (frameStart == 0) frameStart = now;

        while (now - frameStart >= delays[frame]) {
            frameStart += delays[frame];
            frame = (frame + 1) % frames.length;
        }
    }

    private boolean ensureTexture(int index) {
        if (textures[index] != null) return true;

        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(frames[index]));
            imgW = image.getWidth();
            imgH = image.getHeight();

            textures[index] = new DynamicTexture(() -> "HUD+ image", image);
            mc.getTextureManager().register(ids[index], textures[index]);
            return true;
        } catch (IOException e) {
            AddonTemplate.LOG.error("Failed to create HUD image texture", e);
            free();
            return false;
        }
    }


    private static boolean isSupported(String name) {
        String lower = name.toLowerCase();
        for (String ext : EXTENSIONS) if (lower.endsWith(ext)) return true;
        return false;
    }

    private static String[] listImages() {
        File[] files = FOLDER.listFiles((dir, name) -> isSupported(name));
        if (files == null || files.length == 0) return new String[]{"(none)"};

        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].getName();
        Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }


    private void onChoosePressed(boolean pressed) {
        if (!pressed) return;
        choose.set(false); 
        openDialog();
    }

    private void openDialog() {
        if (dialogOpen) return;
        dialogOpen = true;

        Thread thread = new Thread(() -> {
            String imported = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                String[] patterns = {"*.png", "*.jpg", "*.jpeg", "*.webp", "*.gif"};
                PointerBuffer filters = stack.mallocPointer(patterns.length);
                for (String pattern : patterns) filters.put(stack.UTF8(pattern));
                filters.flip();

                String picked = TinyFileDialogs.tinyfd_openFileDialog(
                    "Select an image",
                    System.getProperty("user.home") + File.separator,
                    filters,
                    "Images (png, jpg, webp, gif)",
                    false
                );

                if (picked != null) imported = copyIntoFolder(picked);
            } catch (Throwable e) {
                AddonTemplate.LOG.error("Could not import image", e);
            }

            String result = imported;
            mc.execute(() -> {
                dialogOpen = false;
                if (result != null) {
                    file.set(result);
                    loadedName = null; 
                }
            });
        }, "hud-image-picker");
        thread.setDaemon(true);
        thread.start();
    }

    private static String copyIntoFolder(String path) throws IOException {
        Path source = Path.of(path);
        String name = source.getFileName().toString();
        if (!isSupported(name)) {
            AddonTemplate.LOG.warn("Unsupported image type (use png, jpg, jpeg, webp or gif): {}", path);
            return null;
        }

        FOLDER.mkdirs();
        Path target = FOLDER.toPath().resolve(name);
        if (!Files.exists(target) || !Files.isSameFile(source, target)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return name;
    }


    private void load() {
        String name = file.get();
        loadedName = name;
        int token = ++loadToken;
        free();
        loading = false;

        if (name.isEmpty() || name.equals("(none)")) return;

        File f = new File(name);
        if (!f.isAbsolute()) f = new File(FOLDER, name);
        if (!f.isFile()) {
            AddonTemplate.LOG.warn("HUD image not found: {}", f.getAbsolutePath());
            return;
        }

        loading = true;
        File source = f;
        Thread thread = new Thread(() -> {
            Decoded decoded = null;
            try {
                decoded = decode(source);
            } catch (Throwable e) {
                AddonTemplate.LOG.error("Failed to load HUD image {}", source.getAbsolutePath(), e);
            }

            Decoded result = decoded;
            mc.execute(() -> {
                if (token != loadToken) return; 
                loading = false;
                if (result == null || result.frames().isEmpty()) return;

                int count = result.frames().size();
                delays = new int[count];
                ids = new Identifier[count];
                textures = new DynamicTexture[count];
                for (int i = 0; i < count; i++) {
                    delays[i] = Math.max(20, result.delays().get(i));
                    ids[i] = Identifier.fromNamespaceAndPath("hud-plus", "hud-image-" + instance + "-" + i);
                }

                frame = 0;
                frameStart = 0;
                frames = result.frames().toArray(new byte[0][]);
            });
        }, "hud-image-loader");
        thread.setDaemon(true);
        thread.start();
    }

    private static Decoded decode(File f) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(f)) {
            if (iis == null) throw new IOException("Cannot read " + f.getName());

            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) throw new IOException("No decoder available for " + f.getName());

            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, false, false);

                if (reader.getFormatName().equalsIgnoreCase("gif") && reader.getNumImages(true) > 1) {
                    return decodeGif(reader);
                }
                return decodeStill(reader);
            } finally {
                reader.dispose();
            }
        }
    }

    private static Decoded decodeStill(ImageReader reader) throws IOException {
        int w = reader.getWidth(0);
        int h = reader.getHeight(0);

        ImageReadParam param = reader.getDefaultReadParam();
        int sub = Math.max(w, h) / MAX_SIZE;
        if (sub > 1) param.setSourceSubsampling(sub, sub, 0, 0);

        BufferedImage image = reader.read(0, param);
        int iw = image.getWidth(), ih = image.getHeight();
        int[] pixels = image.getRGB(0, 0, iw, ih, null, 0, iw);

        int longest = Math.max(iw, ih);
        if (longest > MAX_SIZE) {
            double factor = (double) MAX_SIZE / longest;
            int tw = Math.max(1, (int) Math.round(iw * factor));
            int th = Math.max(1, (int) Math.round(ih * factor));
            pixels = downscale(pixels, iw, ih, tw, th);
            iw = tw;
            ih = th;
        }

        List<byte[]> frames = new ArrayList<>();
        frames.add(toPng(pixels, iw, ih));
        return new Decoded(frames, new ArrayList<>(List.of(0)));
    }

    private static Decoded decodeGif(ImageReader reader) throws IOException {
        int count = Math.min(reader.getNumImages(true), MAX_FRAMES);

        int sw = 0, sh = 0;
        IIOMetadata streamMeta = reader.getStreamMetadata();
        if (streamMeta != null) {
            Node root = streamMeta.getAsTree("javax_imageio_gif_stream_1.0");
            Node screen = child(root, "LogicalScreenDescriptor");
            sw = intAttr(screen, "logicalScreenWidth", 0);
            sh = intAttr(screen, "logicalScreenHeight", 0);
        }
        if (sw <= 0 || sh <= 0) {
            sw = reader.getWidth(0);
            sh = reader.getHeight(0);
        }

        int longest = Math.max(sw, sh);
        double factor = longest > GIF_MAX_SIZE ? (double) GIF_MAX_SIZE / longest : 1;
        int tw = Math.max(1, (int) Math.round(sw * factor));
        int th = Math.max(1, (int) Math.round(sh * factor));

        int[] canvas = new int[sw * sh];
        List<byte[]> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            BufferedImage frameImage = reader.read(i);
            Node root = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
            Node desc = child(root, "ImageDescriptor");
            Node gce = child(root, "GraphicControlExtension");

            int left = intAttr(desc, "imageLeftPosition", 0);
            int top = intAttr(desc, "imageTopPosition", 0);
            int delay = intAttr(gce, "delayTime", 10) * 10; 
            if (delay <= 10) delay = 100; 
            String disposal = strAttr(gce, "disposalMethod", "none");

            int[] previous = disposal.equals("restoreToPrevious") ? canvas.clone() : null;

            int fw = frameImage.getWidth(), fh = frameImage.getHeight();
            int[] px = frameImage.getRGB(0, 0, fw, fh, null, 0, fw);
            for (int y = 0; y < fh; y++) {
                int cy = top + y;
                if (cy < 0 || cy >= sh) continue;
                for (int x = 0; x < fw; x++) {
                    int cx = left + x;
                    if (cx < 0 || cx >= sw) continue;
                    int p = px[y * fw + x];
                    if ((p >>> 24) != 0) canvas[cy * sw + cx] = p;
                }
            }

            int[] out = (tw != sw || th != sh) ? downscale(canvas, sw, sh, tw, th) : canvas;
            frames.add(toPng(out, tw, th));
            delays.add(delay);

            if (disposal.equals("restoreToBackgroundColor")) {
                for (int y = 0; y < fh; y++) {
                    int cy = top + y;
                    if (cy < 0 || cy >= sh) continue;
                    for (int x = 0; x < fw; x++) {
                        int cx = left + x;
                        if (cx >= 0 && cx < sw) canvas[cy * sw + cx] = 0;
                    }
                }
            } else if (previous != null) {
                canvas = previous;
            }
        }

        return new Decoded(frames, delays);
    }


    private static Node child(Node parent, String name) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeName().equals(name)) return n;
        }
        return null;
    }

    private static String strAttr(Node node, String attr, String fallback) {
        if (node == null || node.getAttributes() == null) return fallback;
        Node a = node.getAttributes().getNamedItem(attr);
        return a == null ? fallback : a.getNodeValue();
    }

    private static int intAttr(Node node, String attr, int fallback) {
        try {
            return Integer.parseInt(strAttr(node, attr, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }


    private static byte[] toPng(int[] pixels, int w, int h) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, pixels, 0, w);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static int[] downscale(int[] in, int sw, int sh, int tw, int th) {
        int[] out = new int[tw * th];

        for (int y = 0; y < th; y++) {
            int y0 = y * sh / th;
            int y1 = Math.max(y0 + 1, (y + 1) * sh / th);

            for (int x = 0; x < tw; x++) {
                int x0 = x * sw / tw;
                int x1 = Math.max(x0 + 1, (x + 1) * sw / tw);

                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int p = in[yy * sw + xx];
                        a += p >>> 24;
                        r += (p >> 16) & 255;
                        g += (p >> 8) & 255;
                        b += p & 255;
                        n++;
                    }
                }
                out[y * tw + x] = ((int) (a / n) << 24) | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
            }
        }
        return out;
    }

    private void free() {
        if (textures != null) {
            for (int i = 0; i < textures.length; i++) {
                if (textures[i] != null) mc.getTextureManager().release(ids[i]);
            }
        }
        frames = null;
        delays = null;
        ids = null;
        textures = null;
    }
}
