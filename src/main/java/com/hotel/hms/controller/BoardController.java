package com.hotel.hms.controller;

import com.hotel.hms.dto.BoardView;
import com.hotel.hms.service.BoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;

/**
 * 房态大屏（墙挂 / 值班台用）。
 *
 * <p>页面本身只负责壳与样式，数据全部由 {@code /api/board} 提供，
 * 前端每 10 秒重新拉一次——大屏不需要实时推送，轮询足够且更省资源。
 */
@Controller
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    /** 大屏页面 */
    @GetMapping("/board")
    public String page(@RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       Model model) {
        LocalDate day = date == null ? LocalDate.now() : date;
        model.addAttribute("boardDate", day);
        // 传给前端 JS 的日期用 ISO 字符串，避免 LocalDate 被内联成别的东西
        model.addAttribute("boardDateIso", day.toString());
        model.addAttribute("active", "bigboard");
        return "board/index";
    }

    /** 大屏数据（前端轮询） */
    @GetMapping("/api/board")
    @ResponseBody
    public BoardView api(@RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return boardService.snapshot(date);
    }
}
