package com.hotel.hms.common;

/**
 * 业务异常：由 Service 主动抛出，交由全局异常处理器转成友好的页面提示。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
