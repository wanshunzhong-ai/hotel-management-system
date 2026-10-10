package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.common.Pagination;
import com.hotel.hms.dto.CustomerOption;
import com.hotel.hms.entity.Customer;
import com.hotel.hms.entity.Gender;
import com.hotel.hms.entity.IdType;
import com.hotel.hms.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户档案管理。
 */
@Controller
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageIndex = Pagination.pageIndex(page);
        int pageSize = Pagination.pageSize(size);
        Page<Customer> result = Pagination.fetch(
                p -> customerService.search(keyword, p),
                pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "id"));

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("keyword", keyword);
        Pagination.Bases bases = Pagination.bases("/customers", filters, pageSize);

        model.addAttribute("page", result);
        model.addAttribute("pageNumbers", Pagination.window(result));
        model.addAttribute("sizes", Pagination.SIZE_OPTIONS);
        model.addAttribute("pageBase", bases.page());
        model.addAttribute("sizeBase", bases.size());
        model.addAttribute("keyword", keyword);
        model.addAttribute("total", result.getTotalElements());
        model.addAttribute("active", "customers");
        return "customer/list";
    }

    /** 预订页客户选择器的远程搜索（返回精简结构，不含证件号） */
    @GetMapping("/picker")
    @ResponseBody
    public List<CustomerOption> picker(@RequestParam(name = "kw", required = false) String keyword) {
        return customerService.searchForPicker(keyword).stream()
                .map(CustomerOption::of)
                .toList();
    }

    /**
     * 预订页「内联新建客户」。以 JSON 返回结果，前端弹窗原地渲染错误，
     * 避免跳转到 /customers/new 导致已填的预订表单丢失。
     */
    @PostMapping("/quick")
    @ResponseBody
    public Map<String, Object> quickCreate(@Valid Customer customer, BindingResult binding) {
        if (binding.hasErrors()) {
            Map<String, String> errors = new LinkedHashMap<>();
            for (FieldError fe : binding.getFieldErrors()) {
                errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("message", "有 " + errors.size() + " 项需要修正");
            body.put("errors", errors);
            return body;
        }
        try {
            Customer saved = customerService.create(customer);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("message", "客户「" + saved.getName() + "」已建档");
            body.put("customer", CustomerOption.of(saved));
            return body;
        } catch (BusinessException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("message", ex.getMessage());
            body.put("errors", Map.of("idNo", ex.getMessage()));
            return body;
        }
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("customer", new Customer());
        prepareOptions(model);
        model.addAttribute("active", "customers");
        return "customer/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("customer", customerService.getById(id));
        prepareOptions(model);
        model.addAttribute("active", "customers");
        return "customer/form";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("customer", customerService.getById(id));
        model.addAttribute("active", "customers");
        return "customer/detail";
    }

    @PostMapping("/save")
    public String save(@Valid Customer customer, BindingResult binding, Model model, RedirectAttributes ra) {
        if (binding.hasErrors()) {
            prepareOptions(model);
            model.addAttribute("active", "customers");
            return "customer/form";
        }
        try {
            if (customer.getId() == null) {
                customerService.create(customer);
                ra.addFlashAttribute("successMessage", "客户已建档");
            } else {
                customerService.update(customer.getId(), customer);
                ra.addFlashAttribute("successMessage", "客户资料已更新");
            }
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return customer.getId() == null ? "redirect:/customers/new"
                    : "redirect:/customers/" + customer.getId() + "/edit";
        }
        return "redirect:/customers";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            customerService.delete(id);
            ra.addFlashAttribute("successMessage", "客户已删除");
        } catch (BusinessException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/customers";
    }

    private void prepareOptions(Model model) {
        model.addAttribute("genders", Gender.values());
        model.addAttribute("idTypes", IdType.values());
    }
}
