package com.deepseek.pet;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ★★ 离线「导出 → 导入」往返断言（用户追加要求里最硬的一条）。
 *
 * <p>为什么要在桌面 JVM 上跑：真机/模拟器上没法逐次验证"导出 106 个动作的包再导回来
 * 是不是一个不差"。这里用**真代码**跑：
 * <ol>
 *   <li>{@link PetAnimPack#loadOffline} —— 与设备上同一条装配路径
 *       （只是内置基底配置从 APK assets 换成传进来的文本、filesDir 换成传进来的目录 ——
 *       AssetManager 是 final 类、Context 有 104 个抽象方法，桌面 JVM 造不出可用替身）；</li>
 *   <li>{@link ActionSystem#load} + {@link ActionSystem#applyOverrides} —— 有效绑定；</li>
 *   <li>{@link CharacterExporter#plan} + {@link CharacterExporter#write} —— 真的写 zip；</li>
 *   <li>{@link CharacterImporter#installInto} —— 真的解压安装（Context 版本只是转发到它）；</li>
 *   <li>再 {@code loadOffline} 装入导入结果 —— 数"可播动作"。</li>
 * </ol>
 * 两个场景：A 全部素材齐备（要求数量**完全相等**）；B 故意缺一个素材文件
 * （要求它出现在 {@code missing} 里、包里没有它、其余一个不少 —— 不许静默丢）。
 *
 * <p>用法：{@code java com.deepseek.pet.RoundTrip <工作根> <assets/webp 目录> <pet_anim.json> <anim_lines.json>}
 */
public class RoundTrip {

    static File ROOT, ASSET_CLIPS, FILES;
    static String BASE_CFG, BASE_LINES;

    public static void main(String[] args) throws Exception {
        ROOT = new File(args[0]);
        ASSET_CLIPS = new File(args[1]);
        BASE_CFG = read(new File(args[2]));
        BASE_LINES = read(new File(args[3]));
        del(ROOT);
        FILES = new File(ROOT, "files");
        FILES.mkdirs();
        scenario("A", false);
        scenario("B", true);
    }

    static void scenario(String name, boolean dropOne) throws Exception {
        System.out.println("SCENARIO|" + name);
        File base = new File(ROOT, "s" + name);
        File charDir = new File(base, "characters/rt_test");
        File packs = new File(charDir, "anim/clips");
        packs.mkdirs();
        new File(charDir, "memes").mkdirs();
        write(new File(charDir, "character.json"),
                "{\n \"id\": \"rt_test\",\n \"name\": \"往返测试角色\",\n \"author\": \"harness\"\n}\n");
        write(new File(charDir, "anim/pack.json"),
                "{\n \"version\": 2,\n \"scenes\": {\n  \"idle\": [\"A动图\"],\n"
                + "  \"drag\": [\"A动图\", \"B动图\"]\n },\n \"events\": {}\n}\n");
        write(new File(packs, "A动图.webp"), "A-webp-payload");
        write(new File(packs, "B动图.webp"), "B-webp-payload");
        write(new File(charDir, "anim/lines.json"),
                "{\n \"A动图\": [\"台词A1\", \"台词A2\"],\n \"B动图\": [\"台词B\"],\n"
                + " \"不该被导出的动作\": [\"这条不该出现在导出包里\"]\n}\n");
        write(new File(charDir, "memes/memes.json"), "{\n \"微笑\": \"测试表情\"\n}\n");
        write(new File(charDir, "memes/微笑.png"), "meme-png-payload");
        write(new File(charDir, "persona.md"), "# 测试性格\n");

        // 用户素材目录（覆盖层里）：一个真的动作 +（B 场景）故意缺一个
        // ★ 动作名**不带扩展名**（素材层按"名字.扩展名"找文件）—— 第一版把 id 写成
        //   "缺失的.png"，于是找的是 "缺失的.png.png"，两个场景都假报 missing。
        File userClips = SceneBindings.clipsDirIn(FILES, "rt_test");
        write(new File(userClips, "用户加的.png"), "user-png-payload");
        File missing = new File(userClips, "缺失的.png");
        if (dropOne) { if (missing.exists()) missing.delete(); } else { write(missing, "missing-payload"); }

        File ov = SceneBindings.fileIn(FILES, "rt_test");
        write(ov, "{\n \"version\": 1,\n \"scenes\": {\n"
                + "  \"drag\": [\"A动图\", \"缺失的\"],\n"
                + "  \"click\": [\"用户加的\"],\n"
                + "  \"pool\": [{\"id\": \"A动图\", \"weight\": 3}]\n },\n"
                + " \"events\": { \"whisper\": [\"B动图\"] }\n}\n");

        // ★ 待办 0m ①：形象也要跟着走（P5 把它放在**覆盖层** filesDir/appearance/<角色 id>/）
        //   这里照运行期的真实路径造一份：来源声明 + 一张素材图。
        File appDir = AppearanceStore.dirIn(FILES, "rt_test");
        appDir.mkdirs();
        write(new File(appDir, "appearance.json"), "{\"version\":1,\"source\":\"image\"}");
        write(new File(appDir, "image.png"), "face-png-payload");

        // ① 有效绑定（= 运行期同一套装配语义）
        PetAnimPack pk = new PetAnimPack();
        pk.loadOffline(FILES, BASE_CFG, BASE_LINES, charDir);
        ActionSystem as = new ActionSystem();
        as.attach(pk);
        as.load(pk.configRoot());
        as.applyOverrides(new JSONObject(read(ov)));
        System.out.println("PK_READY|" + pk.isReady() + "|" + pk.allNames().size());
        System.out.println("OVERRIDES|" + as.overrideCount());

        // ② 计划
        CharacterExporter.Plan plan = CharacterExporter.plan(as);
        System.out.println("PLAN_COUNT|" + plan.clipIds.size());
        for (String scene : plan.scenes.keySet()) System.out.println("PLAN|" + row(scene, plan.scenes.get(scene)));
        for (String scene : plan.events.keySet()) System.out.println("PLAN_EV|" + row(scene, plan.events.get(scene)));
        System.out.println("PLAN_CLIPS|" + join(plan.clipIds));

        // ③ 写包（素材解析：用户素材目录 → 角色包 clips → 资源目录[代替 APK assets]）
        File zip = new File(base, "export.zip");
        CharacterExporter.Result r = new CharacterExporter.Result();
        FileOutputStream fos = new FileOutputStream(zip);
        CharacterExporter.write(fos, plan,
                CharacterExporter.dirSource(userClips, packs, ASSET_CLIPS),
                pk, charDir, appDir, "rt_test", "往返测试角色", r);
        fos.close();
        System.out.println("EXPORT|ok=" + r.ok + " files=" + r.files + " clips=" + r.clips
                + " scenes=" + r.scenes + " bytes=" + r.bytes
                + " appear=" + r.appearanceFiles
                + " missing=" + join(r.missing) + " err=" + r.error);

        // ④ 看包里到底有什么
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e;
        int clipEntries = 0;
        while ((e = zis.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            byte[] data = readEntry(zis);          // ★ 不能 close，关了就读不到下一个条目
            if (e.getName().startsWith("anim/clips/")) clipEntries++;
            System.out.println("ENTRY|" + e.getName() + "|" + data.length);
            if (e.getName().equals("anim/pack.json")) block("PACKJSON", new String(data, "UTF-8"));
            if (e.getName().equals("anim/lines.json")) block("LINESJSON", new String(data, "UTF-8"));
            if (e.getName().equals("character.json")) block("CHARJSON", new String(data, "UTF-8"));
        }
        zis.close();
        System.out.println("ZIP_CLIP_ENTRIES|" + clipEntries);

        // ⑤ 真的走一遍导入器（离线核心：Context 版本就是转发到它）
        //    ★ 形象装到一个**全新的** files 目录（files2）：证明"脸"是**包带过来的**，
        //      而不是原来那个目录里剩下来的。
        File files2 = new File(base, "files2");
        files2.mkdirs();
        CharacterImporter.Result r2 = CharacterImporter.installInto(
                new File(base, "tmpbase"), new File(base, "characters"), files2,
                new FileInputStream(zip), false);
        System.out.println("IMPORT|ok=" + r2.ok + " id=" + r2.id + " clips=" + r2.clips
                + " synth=" + r2.synthesized + " missing=" + join(r2.missing) + " err=" + r2.error);
        System.out.println("INSTALLED_DIR|" + (r2.dir == null ? "-" : r2.dir.getAbsolutePath()));

        // ⑤b 形象落盘 + 读得回来（路径必须与运行期同一套：AppearanceStore.dirIn）
        File app2 = AppearanceStore.dirIn(files2, r2.id);
        System.out.println("APPEAR|flag=" + r2.appearance + " files=" + r2.appearanceFiles
                + " dir=" + app2.getAbsolutePath()
                + " json=" + new File(app2, "appearance.json").isFile()
                + " image=" + new File(app2, "image.png").isFile()
                + " src=" + AppearanceStore.sourceIn(files2, r2.id)
                + " payload=" + (new File(app2, "image.png").isFile()
                        ? read(new File(app2, "image.png")) : "-"));

        // ⑥ 装入导入结果 → 数"可播动作"
        PetAnimPack pk2 = new PetAnimPack();
        pk2.loadOffline(FILES, BASE_CFG, BASE_LINES, r2.dir);
        System.out.println("PLAYABLE|" + pk2.allNames().size() + "|" + join(pk2.allNames()));
        System.out.println("SCENARIO_END|" + name);
    }

    // ==================== 小工具 ====================

    static String row(String scene, List<SceneBindings.Bind> binds) {
        StringBuilder sb = new StringBuilder(scene).append("|");
        for (SceneBindings.Bind b : binds) {
            if (sb.charAt(sb.length() - 1) != '|') sb.append(", ");
            sb.append(b.id).append("x").append(b.weight);
        }
        return sb.toString();
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        if (l != null) for (String s : l) { if (sb.length() > 0) sb.append(" ## "); sb.append(s); }
        return sb.toString();
    }

    static void block(String tag, String text) {
        System.out.println(tag + "_BEGIN");
        System.out.print(text);
        if (!text.endsWith("\n")) System.out.println();
        System.out.println(tag + "_END");
    }

    static String read(File f) throws Exception {
        return new String(readAll(new FileInputStream(f)), "UTF-8");
    }

    static byte[] readAll(InputStream in) throws Exception {
        byte[] b = readEntry(in);
        in.close();
        return b;
    }

    /** 读干一个流但**不关闭**它（ZipInputStream 的条目要一个个读下去） */
    static byte[] readEntry(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        return bos.toByteArray();
    }

    static void write(File f, String s) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        out.write(s.getBytes("UTF-8"));
        out.close();
    }

    static void del(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) del(c);
        f.delete();
    }
}
