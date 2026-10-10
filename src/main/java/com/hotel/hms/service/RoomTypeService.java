package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.RoomType;
import com.hotel.hms.repository.RoomRepository;
import com.hotel.hms.repository.RoomTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 房型服务。
 */
@Service
@RequiredArgsConstructor
public class RoomTypeService {

    private final RoomTypeRepository roomTypeRepository;
    private final RoomRepository roomRepository;

    @Transactional(readOnly = true)
    public List<RoomType> findAll() {
        return roomTypeRepository.findAllByOrderByPriceAsc();
    }

    @Transactional(readOnly = true)
    public List<RoomType> findEnabled() {
        return roomTypeRepository.findByEnabledTrueOrderByPriceAsc();
    }

    @Transactional(readOnly = true)
    public RoomType getById(Long id) {
        return roomTypeRepository.findById(id)
                .orElseThrow(() -> new BusinessException("房型不存在"));
    }

    @Transactional
    public RoomType create(RoomType form) {
        if (roomTypeRepository.existsByName(form.getName())) {
            throw new BusinessException("房型「" + form.getName() + "」已存在");
        }
        form.setId(null);
        return roomTypeRepository.save(form);
    }

    @Transactional
    public RoomType update(Long id, RoomType form) {
        RoomType db = getById(id);
        if (roomTypeRepository.existsByNameAndIdNot(form.getName(), id)) {
            throw new BusinessException("房型「" + form.getName() + "」已存在");
        }
        db.setName(form.getName());
        db.setBedType(form.getBedType());
        db.setArea(form.getArea());
        db.setCapacity(form.getCapacity());
        db.setPrice(form.getPrice());
        db.setDescription(form.getDescription());
        db.setEnabled(form.getEnabled() == null ? Boolean.TRUE : form.getEnabled());
        return roomTypeRepository.save(db);
    }

    @Transactional
    public void delete(Long id) {
        RoomType db = getById(id);
        long rooms = roomRepository.countByRoomTypeId(db.getId());
        if (rooms > 0) {
            throw new BusinessException("该房型下还有 " + rooms + " 间客房，无法删除");
        }
        roomTypeRepository.delete(db);
    }
}
