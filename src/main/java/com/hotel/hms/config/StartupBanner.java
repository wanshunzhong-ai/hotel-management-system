package com.hotel.hms.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 应用启动完成后在控制台打印访问入口链接。
 * <p>
 * 端口取自实际启动的 Web 容器（而非配置文件字面值），因此命令行 {@code --server.port}、
 * 环境变量 {@code SERVER_PORT} 覆盖后打印的仍是真实可用地址。
 * 可通过 {@code hotel.startup.print-banner=false} 关闭。
 */
@Slf4j
@Component
public class StartupBanner implements ApplicationListener<ApplicationReadyEvent> {

    private static final String LINE = "════════════════════════════════════════════════════════════════";

    private final String hotelName;
    private final boolean demoDataEnabled;
    private final boolean bannerEnabled;

    public StartupBanner(@Value("${hotel.name:酒店管理系统}") String hotelName,
                         @Value("${hotel.init-demo-data:true}") boolean demoDataEnabled,
                         @Value("${hotel.startup.print-banner:true}") boolean bannerEnabled) {
        this.hotelName = hotelName;
        this.demoDataEnabled = demoDataEnabled;
        this.bannerEnabled = bannerEnabled;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!bannerEnabled) {
            return;
        }
        if (!(event.getApplicationContext() instanceof ServletWebServerApplicationContext ctx)) {
            return;
        }
        WebServer webServer = ctx.getWebServer();
        if (webServer == null) {
            return;
        }

        int port = webServer.getPort();
        String contextPath = contextPathOf(ctx);
        String localBase = "http://localhost:" + port + contextPath;

        StringBuilder sb = new StringBuilder(512);
        sb.append('\n').append(LINE).append('\n');
        sb.append("  ✔ ").append(hotelName).append(" · 酒店管理系统 启动成功\n");
        sb.append(LINE).append('\n');
        sb.append("  访问入口    ").append(localBase).append("/login\n");
        sb.append("  数据看板    ").append(localBase).append("/dashboard\n");

        List<String> lanUrls = lanBaseUrls(port, contextPath);
        if (!lanUrls.isEmpty()) {
            for (int i = 0; i < lanUrls.size(); i++) {
                sb.append(i == 0 ? "  局域网访问  " : "              ").append(lanUrls.get(i)).append("/login\n");
            }
        }

        if (demoDataEnabled) {
            sb.append("────────────────────────────────────────────────────────────────\n");
            sb.append("  演示账号    admin / admin123              （管理员）\n");
            sb.append("              reception / 123456            （前台）\n");
            sb.append("              housekeeping / 123456         （客房）\n");
        }

        sb.append(LINE);

        log.info(sb.toString());
    }

    /** 上下文路径规范化为 "" 或 "/xxx"。 */
    private String contextPathOf(ServletWebServerApplicationContext ctx) {
        String path = ctx.getServletContext().getContextPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /** 收集本机非回环 IPv4 地址，拼成局域网访问地址。 */
    private List<String> lanBaseUrls(int port, String contextPath) {
        List<String> urls = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress() && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip.startsWith("169.254.")) {   // 链路本地地址，无意义
                            continue;
                        }
                        String url = "http://" + ip + ":" + port + contextPath;
                        if (!urls.contains(url)) {
                            urls.add(url);
                        }
                    }
                }
            }
        } catch (SocketException e) {
            log.debug("枚举网卡失败，跳过局域网地址输出", e);
        }
        return urls.size() > 3 ? urls.subList(0, 3) : urls;
    }
}
