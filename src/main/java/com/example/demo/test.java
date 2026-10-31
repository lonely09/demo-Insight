import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class test {
    public static void main(String[] args) {
        // 目标大小：64K tokens，约等于 25 万字符 (256,000)
        int targetChars = 256000;

        // 拼接的基础语句
        String baseSentence = "The quick brown fox jumps over the lazy dog. Data analysis requires clear context. ";

        StringBuilder sb = new StringBuilder();
        sb.append("【系统指令】：请仔细阅读以下长文，读完后只在结尾回复：“我已成功阅读 64K 上下文”。\n\n【长文开始】\n");

        // 循环填充直到接近目标字符数
        while (sb.length() < targetChars) {
            sb.append(baseSentence);
        }

        sb.append("\n【长文结束】\n请回复：我已成功阅读 64K 上下文。");

        // 输出到桌面
        String desktopPath = System.getProperty("user.home") + "\\Desktop\\test_64k.txt";
        Path path = Paths.get(desktopPath);

        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(sb.toString());
            System.out.println("✅ 生成成功！");
            System.out.println("文件位置: " + desktopPath);
            System.out.println("文件总字符数: " + sb.length());
        } catch (Exception e) {
            System.out.println("❌ 生成失败: " + e.getMessage());
        }
    }
}