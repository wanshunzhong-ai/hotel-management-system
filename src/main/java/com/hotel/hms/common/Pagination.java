package com.hotel.hms.common;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 列表分页辅助：构造分页链接前缀、计算页码窗口、归一化分页与排序参数。
 *
 * <p>分页链接在模板里手工拼接很容易踩两个坑：中文关键词没有 URL 编码、
 * 筛选条件在翻页后丢失。这里统一在服务端生成「路径 + 已编码筛选参数 + 连接符」的前缀，
 * 模板只需追加 {@code page=N}，翻页时筛选条件自动保留。
 */
public final class Pagination {

    /** 页面「每页条数」下拉提供的选项 */
    public static final List<Integer> SIZE_OPTIONS = List.of(10, 20, 50);

    /** 默认每页条数 */
    public static final int DEFAULT_SIZE = 20;

    /** 单页允许的最大条数，挡住 {@code size=100000} 这类把整张表拉进内存的请求 */
    public static final int MAX_SIZE = 100;

    /** 页码窗口的左右半径 */
    public static final int RADIUS = 2;

    private Pagination() {
    }

    /**
     * 生成分页链接前缀：{@code 路径 + 已编码的筛选参数 + 连接符}。
     *
     * <p>例如 {@code base("/reservations", Map.of("keyword", "张"))}
     * 得到 {@code /reservations?keyword=%E5%BC%A0&}；无筛选条件时得到 {@code /reservations?}。
     * 模板里拼接 {@code page=2} 即得完整链接。
     */
    public static String base(String path, Map<String, ?> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        params.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            String text = String.valueOf(value);
            if (text.isBlank()) {
                return;
            }
            builder.queryParam(key, text);
        });
        String uri = builder.build().encode(StandardCharsets.UTF_8).toUriString();
        return uri.indexOf('?') < 0 ? uri + "?" : uri + "&";
    }

    /**
     * 分页条需要的两个链接前缀。
     *
     * @param page 含 {@code size}——用于页码链接（翻页时保留每页条数）
     * @param size 不含 {@code size}——用于「每页 N 条」链接，避免同名参数重复导致第一个生效
     */
    public record Bases(String page, String size) {
    }

    /** 一次性生成分页条的两个链接前缀 */
    public static Bases bases(String path, Map<String, ?> filters, int size) {
        String sizeBase = base(path, filters);
        Map<String, Object> withSize = new LinkedHashMap<>(filters);
        withSize.put("size", size);
        return new Bases(base(path, withSize), sizeBase);
    }

    /**
     * 计算当前页附近的页码窗口，页数足够时窗口宽度稳定为 {@code 2 * RADIUS + 1}。
     * 首页/末页附近会自动向内补齐，避免出现只有 1~2 个页码按钮的塌陷。
     */
    public static List<Integer> window(Page<?> page) {
        return window(page, RADIUS);
    }

    public static List<Integer> window(Page<?> page, int radius) {
        int total = page.getTotalPages();
        if (total <= 1) {
            return List.of();
        }
        int span = radius * 2;
        int start = Math.max(0, page.getNumber() - radius);
        int end = Math.min(total - 1, start + span);
        start = Math.max(0, end - span);
        List<Integer> numbers = new ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) {
            numbers.add(i);
        }
        return numbers;
    }

    /** 归一化页码，负数或空值回落到第一页，避免前端乱传 page 触发异常。 */
    public static int pageIndex(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    /**
     * 归一化每页条数。
     *
     * <p>不限于 {@link #SIZE_OPTIONS}（那只是页面下拉的候选），
     * 任何 {@code 1..}{@value #MAX_SIZE} 的整数都接受，方便接口调用方按需取数；
     * 越界或非法值回落到 {@value #DEFAULT_SIZE}。上限的存在是为了防止
     * {@code ?size=100000} 把整张表拉进内存。
     */
    public static int pageSize(Integer size) {
        if (size == null || size < 1 || size > MAX_SIZE) {
            return DEFAULT_SIZE;
        }
        return size;
    }

    /**
     * 从白名单里解析排序键。非法输入（含 null）回落到 {@code defaultKey}，
     * 避免把用户输入直接当作实体属性名拼进 Sort（越权排序 / 属性不存在报错）。
     *
     * <p>用法：{@code String key = Pagination.sortKey(sort, SORTS, "latest"); Sort order = SORTS.get(key);}
     */
    public static String sortKey(String sortKey, Map<String, Sort> allowed, String defaultKey) {
        return sortKey != null && allowed.containsKey(sortKey) ? sortKey : defaultKey;
    }

    /**
     * 执行分页查询，并把越界页码夹回最后一页。
     *
     * <p>删掉几条记录后再点「下一页」很容易翻过头，此时 Spring Data 会返回一个
     * {@code content} 为空但 {@code totalPages} 正确的页对象——列表空白、分页条上还没有高亮项，
     * 用户会以为"数据没了"。这里发现越界就自动回到最后一页。
     *
     * @param loader 以 {@link Pageable} 为入参的查询函数
     */
    public static <T> Page<T> fetch(Function<Pageable, Page<T>> loader,
                                    int pageIndex, int pageSize, Sort sort) {
        Page<T> result = loader.apply(PageRequest.of(pageIndex, pageSize, sort));
        int totalPages = result.getTotalPages();
        if (pageIndex > 0 && totalPages > 0 && pageIndex >= totalPages) {
            return loader.apply(PageRequest.of(totalPages - 1, pageSize, sort));
        }
        return result;
    }
}
