package com.flower.learnhub;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
    static ResponseEntity<ApiModels.Error> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiModels.Error(code, message));
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiModels.Error> api(ApiException ex) { return response(ex.status(), ex.code(), ex.getMessage()); }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            HandlerMethodValidationException.class})
    ResponseEntity<ApiModels.Error> invalid(Exception ex) {
        return response(400, "VALIDATION_ERROR", "请求参数不合法，请检查必填项、长度与数值范围");
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiModels.Error> tooLarge(Exception ex) { return response(413, "FILE_TOO_LARGE", "文件不能超过 10MB"); }
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ApiModels.Error> multipart(Exception ex) { return response(400, "INVALID_UPLOAD", "上传格式无效，请使用 multipart file 字段"); }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiModels.Error> duplicate(Exception ex) { return response(409, "NAME_CONFLICT", "知识库名称已存在，请使用其他名称"); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiModels.Error> missing(Exception ex) { return response(404, "NOT_FOUND", "资源不存在"); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiModels.Error> method(Exception ex) { return response(405, "METHOD_NOT_ALLOWED", "不支持此请求方法"); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiModels.Error> media(Exception ex) { return response(415, "UNSUPPORTED_MEDIA_TYPE", "不支持此请求格式"); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiModels.Error> database(Exception ex) { return response(500, "STORAGE_ERROR", "数据操作失败，请稍后重试"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiModels.Error> unexpected(Exception ex) { return response(500, "INTERNAL_ERROR", "请求处理失败，请稍后重试"); }
}

@RestController
class JsonErrorController implements ErrorController {
    @RequestMapping("/error")
    ResponseEntity<ApiModels.Error> error(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = value instanceof Integer code && code >= 400 && code <= 599 ? code : 500;
        return ApiErrors.response(status, "HTTP_ERROR", "请求无法处理");
    }
}
