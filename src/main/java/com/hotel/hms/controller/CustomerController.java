package com.hotel.hms.controller;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.Customer;
import com.hotel.hms.entity.Gender;
import com.hotel.hms.entity.IdType;
import com.hotel.hms.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 客户档案管理。
 */
@Controller
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword, Model model) {
        model.addAttribute("customers", customerService.search(keyword));
        model.addAttribute("keyword", keyword);
        model.addAttribute("total", customerService.count());
        model.addAttribute("active", "customers");
        return "customer/list";
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
