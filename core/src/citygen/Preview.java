package citygen;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** 把规划结果画成一张俯视蓝图，用于快速判断分区、路网、地块是否合理。 */
public final class Preview {

    private Preview() {
    }

    public static void write(CityPlan plan, Path out, int scale) throws IOException {
        writeRegion(plan, out, scale, 0, 0, plan.size, plan.size, true);
    }

    /**
     * 建筑层俯视图：铺装为浅灰、道路为深灰，建筑按高度从浅到深着色。
     *
     * <p>这张图是给建筑层做验收用的——只看分区图分不出「楼盖歪了」和「楼没盖」，
     * 而高度着色一眼就能看出塔楼是否集中在 CBD、住宅是否矮而密、工业是否低矮成片。
     */
    public static void writeHeights(CityPlan plan, Path out, int scale) throws IOException {
        int size = plan.size;
        int w = size * scale;
        int h = size * scale;
        int[] px = new int[w * h];

        final int pavement = 0xC9C9CE;

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                Zone zone = plan.zoning.zoneAt(x, z);
                int c = switch (zone) {
                    case GREEN -> 0x6FA86A;
                    case WATER -> 0x4B7BB0;
                    default -> pavement;
                };
                fill(px, w, x, z, scale, c);
            }
        }

        // 最高的那栋决定色阶上限，避免整图灰成一片
        int maxHeight = 1;
        for (Parcels.Parcel p : plan.parcels.list) {
            maxHeight = Math.max(maxHeight, p.height);
        }

        for (Parcels.Parcel p : plan.parcels.list) {
            if (p.height <= 0) {
                continue;
            }
            double t = Math.min(1.0, p.height / (double) maxHeight);
            int c = lerp(0xDCE3EC, 0x24304A, Math.pow(t, 0.7));
            for (int z = p.y + p.setback; z < p.y + p.h - p.setback; z++) {
                for (int x = p.x + p.setback; x < p.x + p.w - p.setback; x++) {
                    fill(px, w, x, z, scale, c);
                }
            }
        }

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (plan.roads.at(x, z)) {
                    fill(px, w, x, z, scale, roadColor(plan.roads.gradeAt(x, z)));
                }
            }
        }

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, px, 0, w);

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(255, 255, 255, 235));
        g.fillRoundRect(18, 16, 310, 44, 8, 8);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        g.setColor(new Color(0x1F, 0x1F, 0x24));
        g.drawString("建筑高度图 · 最高 " + maxHeight + " 格（约 " + (maxHeight / 4) + " 层）", 32, 44);
        g.dispose();

        ImageIO.write(img, "png", out.toFile());
    }

    private static int lerp(int a, int b, double t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** 道路等级配色：越高级越深，一眼能看出高速/主干/次干/支路的层次。 */
    private static int roadColor(byte grade) {
        return switch (grade) {
            case Roads.HIGHWAY -> 0x1B1B22;
            case Roads.TRUNK -> 0x30303A;
            case Roads.SECONDARY -> 0x44444F;
            default -> 0x5C5C68;
        };
    }

    /** 输出指定区域的放大图，用于贴近检查地块与路网的贴合情况。 */
    public static void writeRegion(CityPlan plan, Path out, int scale,
                                   int x0, int z0, int rw, int rh, boolean decorate) throws IOException {
        int size = plan.size;
        int w = rw * scale;
        int h = rh * scale;
        int[] px = new int[w * h];

        for (int z = z0; z < z0 + rh; z++) {
            for (int x = x0; x < x0 + rw; x++) {
                fill(px, w, x - x0, z - z0, scale, shade(plan.zoning.zoneAt(x, z).rgb, 0.93));
            }
        }

        for (Parcels.Parcel p : plan.parcels.list) {
            if (p.x + p.w <= x0 || p.y + p.h <= z0 || p.x >= x0 + rw || p.y >= z0 + rh) {
                continue;
            }
            double k = 1.0 - 0.16 * Math.min(1.0, p.far / 8.0);
            int c = shade(p.zone.rgb, k);
            for (int z = p.y; z < p.y + p.h; z++) {
                for (int x = p.x; x < p.x + p.w; x++) {
                    fill(px, w, x - x0, z - z0, scale, c);
                }
            }
            rect(px, w, (p.x - x0) * scale, (p.y - z0) * scale, p.w * scale, p.h * scale, 0x1A1A1E);
        }

        for (int z = z0; z < z0 + rh; z++) {
            for (int x = x0; x < x0 + rw; x++) {
                if (plan.roads.at(x, z)) {
                    fill(px, w, x - x0, z - z0, scale, 0x5A5A66);
                }
            }
        }

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, px, 0, w);

        if (decorate) {
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            drawTitle(g, plan);
            drawLegend(g, plan, w);
            g.dispose();
        }

        ImageIO.write(img, "png", out.toFile());
    }

    private static void drawTitle(Graphics2D g, CityPlan plan) {
        g.setColor(new Color(255, 255, 255, 235));
        g.fillRoundRect(18, 16, 268, 66, 8, 8);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        g.setColor(new Color(0x1F, 0x1F, 0x24));
        g.drawString("megachunk " + plan.size + "×" + plan.size + " 格", 32, 40);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        g.setColor(new Color(0x55, 0x55, 0x5C));
        g.drawString("seed " + plan.seed + " · 按区块可查询", 32, 60);
    }

    private static void drawLegend(Graphics2D g, CityPlan plan, int w) {
        Map<Zone, Integer> totals = plan.zoneTotals();
        int rows = Zone.values().length;
        int panelW = 186;
        int panelH = 30 + rows * 22;
        int x0 = w - panelW - 18;
        int y0 = 16;

        g.setColor(new Color(255, 255, 255, 235));
        g.fillRoundRect(x0, y0, panelW, panelH, 8, 8);

        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        g.setColor(new Color(0x1F, 0x1F, 0x24));
        g.drawString("用地构成", x0 + 14, y0 + 22);

        int total = plan.size * plan.size;
        int y = y0 + 40;
        for (Zone z : Zone.values()) {
            g.setColor(new Color(z.rgb));
            g.fillRect(x0 + 14, y - 10, 12, 12);
            g.setColor(new Color(0x33, 0x33, 0x3A));
            int n = totals.getOrDefault(z, 0);
            String pct = String.format("%.1f%%", 100.0 * n / total);
            g.drawString(z.label, x0 + 34, y);
            g.drawString(pct, x0 + 132, y);
            y += 22;
        }
    }

    private static void fill(int[] px, int w, int cx, int cz, int scale, int color) {
        int h = px.length / w;
        int x0 = cx * scale;
        int z0 = cz * scale;
        if (x0 < 0 || z0 < 0 || x0 + scale > w || z0 + scale > h) {
            return;
        }
        for (int dz = 0; dz < scale; dz++) {
            int row = (z0 + dz) * w + x0;
            for (int dx = 0; dx < scale; dx++) {
                px[row + dx] = color;
            }
        }
    }

    private static void rect(int[] px, int w, int x0, int y0, int rw, int rh, int color) {
        int h = px.length / w;
        for (int x = Math.max(0, x0); x < x0 + rw && x < w; x++) {
            int y1 = y0 + rh - 1;
            if (y0 >= 0 && y0 < h) {
                px[y0 * w + x] = color;
            }
            if (y1 >= 0 && y1 < h) {
                px[y1 * w + x] = color;
            }
        }
        for (int y = Math.max(0, y0); y < y0 + rh && y < h; y++) {
            int x1 = x0 + rw - 1;
            if (x0 >= 0 && x0 < w) {
                px[y * w + x0] = color;
            }
            if (x1 >= 0 && x1 < w) {
                px[y * w + x1] = color;
            }
        }
    }

    private static int shade(int rgb, double k) {
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * k);
        int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * k);
        int b = (int) Math.min(255, (rgb & 0xFF) * k);
        return (r << 16) | (g << 8) | b;
    }
}
