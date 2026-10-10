package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.Customer;
import com.hotel.hms.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 客户档案服务。
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;

    @Transactional(readOnly = true)
    public List<Customer> search(String keyword) {
        String kw = normalize(keyword);
        return kw == null ? customerRepository.findAllByOrderByIdDesc() : customerRepository.search(kw);
    }

    @Transactional(readOnly = true)
    public Customer getById(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new BusinessException("客户不存在"));
    }

    @Transactional(readOnly = true)
    public long count() {
        return customerRepository.count();
    }

    @Transactional
    public Customer create(Customer form) {
        if (customerRepository.existsByIdNo(form.getIdNo())) {
            throw new BusinessException("证件号「" + form.getIdNo() + "」已登记过客户");
        }
        form.setId(null);
        return customerRepository.save(form);
    }

    @Transactional
    public Customer update(Long id, Customer form) {
        Customer db = getById(id);
        if (customerRepository.existsByIdNoAndIdNot(form.getIdNo(), id)) {
            throw new BusinessException("证件号「" + form.getIdNo() + "」已被其他客户使用");
        }
        db.setName(form.getName());
        db.setGender(form.getGender());
        db.setIdType(form.getIdType());
        db.setIdNo(form.getIdNo());
        db.setPhone(form.getPhone());
        db.setEmail(form.getEmail());
        db.setAddress(form.getAddress());
        db.setMemberLevel(form.getMemberLevel());
        db.setRemark(form.getRemark());
        return customerRepository.save(db);
    }

    @Transactional
    public void delete(Long id) {
        Customer db = getById(id);
        customerRepository.delete(db);
    }

    private String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }
}
