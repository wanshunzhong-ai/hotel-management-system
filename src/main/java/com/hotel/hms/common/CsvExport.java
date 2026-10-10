package com.hotel.hms.common;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV 导出工具（第三批 §3.7）。
 *
 * <p>两个容易踩的坑在这里一次性处理掉：
 * <ul>
 *     <li><b>中文乱码</b>：输出前加 UTF-8 BOM，Excel 双击打开才不会变成乱码；</li>
 *     <li><b>中文文件名</b>：{@code Content-Disposition} 的 {@code filename} 只允许 ASCII，
 *         中文名必须写成 {@code filename*=UTF-8''<百分号编码>}。</li>
 * </ul>
 */
public final class CsvExport {

    private CsvExport() {
    }

    /** 组装带 BOM 的 CSV 字节流 */
    public static byte[] bytes(List<String> headers, List<List<?>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        appendRow(sb, headers);
        for (List<?> row : rows) {
            appendRow(sb, row);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 生成下载响应 */
    public static ResponseEntity<byte[]> download(String filename, List<String> headers, List<List<?>> rows) {
        String encoded;
        try {
            encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException ex) {
            encoded = "export.csv";
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"export.csv\"; filename*=UTF-8''" + encoded)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes(headers, rows));
    }

    private static void appendRow(StringBuilder sb, List<?> cells) {
        if (cells == null) {
            sb.append('\n');
            return;
        }
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells.get(i)));
        }
        // 统一用 \r\n，Excel 兼容性最好
        sb.append("\r\n");
    }

    /** RFC 4180 转义：含逗号/引号/换行的字段用双引号包裹，内部引号翻倍 */
    private static String escape(Object cell) {
        if (cell == null) {
            return "";
        }
        String text = String.valueOf(cell);
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
