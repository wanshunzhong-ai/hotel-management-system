package com.hotel.hms.service;

import com.hotel.hms.repository.BillRepository;
import com.hotel.hms.repository.InvoiceRepository;
import com.hotel.hms.repository.OutsourceBatchRepository;
import com.hotel.hms.repository.ReservationRepository;
import com.hotel.hms.repository.ShiftHandoverRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 业务单号生成器：前缀 + yyyyMMdd + 4 位流水，冲突时重试。
 */
@Service
@RequiredArgsConstructor
public class SerialNumberService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReservationRepository reservationRepository;
    private final BillRepository billRepository;
    private final InvoiceRepository invoiceRepository;
    private final ShiftHandoverRepository shiftHandoverRepository;
    private final OutsourceBatchRepository outsourceBatchRepository;

    public String nextOrderNo() {
        String datePart = LocalDate.now().format(DATE_FMT);
        for (int i = 0; i < 30; i++) {
            String candidate = "RS" + datePart + randomSuffix();
            if (!reservationRepository.existsByOrderNo(candidate)) {
                return candidate;
            }
        }
        return "RS" + datePart + System.currentTimeMillis() % 100000L;
    }

    public String nextBillNo() {
        String datePart = LocalDate.now().format(DATE_FMT);
        for (int i = 0; i < 30; i++) {
            String candidate = "BL" + datePart + randomSuffix();
            if (!billRepository.existsByBillNo(candidate)) {
                return candidate;
            }
        }
        return "BL" + datePart + System.currentTimeMillis() % 100000L;
    }

    /** 发票号：IV202610110001 */
    public String nextInvoiceNo() {
        String datePart = LocalDate.now().format(DATE_FMT);
        for (int i = 0; i < 30; i++) {
            String candidate = "IV" + datePart + randomSuffix();
            if (!invoiceRepository.existsByInvoiceNo(candidate)) {
                return candidate;
            }
        }
        return "IV" + datePart + System.currentTimeMillis() % 100000L;
    }

    /** 交接班单号：SH202610110001 */
    public String nextShiftNo() {
        String datePart = LocalDate.now().format(DATE_FMT);
        for (int i = 0; i < 30; i++) {
            String candidate = "SH" + datePart + randomSuffix();
            if (!shiftHandoverRepository.existsByShiftNo(candidate)) {
                return candidate;
            }
        }
        return "SH" + datePart + System.currentTimeMillis() % 100000L;
    }

    /** 布草外送批次号：WX202610110001 */
    public String nextOutsourceNo() {
        String datePart = LocalDate.now().format(DATE_FMT);
        for (int i = 0; i < 30; i++) {
            String candidate = "WX" + datePart + randomSuffix();
            if (!outsourceBatchRepository.existsByBatchNo(candidate)) {
                return candidate;
            }
        }
        return "WX" + datePart + System.currentTimeMillis() % 100000L;
    }

    private String randomSuffix() {
        return String.format("%04d", ThreadLocalRandom.current().nextInt(1, 10000));
    }
}
