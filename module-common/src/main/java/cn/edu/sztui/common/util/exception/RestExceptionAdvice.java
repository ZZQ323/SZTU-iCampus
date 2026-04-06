package cn.edu.sztui.common.util.exception;

import cn.edu.sztui.common.util.log.LogMark;
import cn.edu.sztui.common.util.result.Result;
import cn.hutool.core.util.ObjectUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.dubbo.remoting.RemotingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.NoHandlerFoundException;
import java.nio.file.AccessDeniedException;

//@Slf4j
@RestControllerAdvice
public class RestExceptionAdvice {
    private static final Logger log = LoggerFactory.getLogger(RestExceptionAdvice.class);
    @Value("${spring.profiles.active:}")
    private String active;
    public static final String REST_EXCEPTION = "rest_exception";
    public static final String PRD = "prd";

    // ============ SSE 超时异常处理（静默） ============

    /**
     * ⭐ 处理 SSE 异步请求超时
     *
     * SSE 连接超时是正常行为，不需要返回错误响应。
     * 因为 SSE 连接已经关闭，无法写入响应，尝试写入会抛出 HttpMediaTypeNotAcceptableException。
     *
     * 这里只记录 DEBUG 日志，不做任何响应处理。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncRequestTimeoutException(
            AsyncRequestTimeoutException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        // SSE 超时是正常行为，静默处理
        // 只记录 DEBUG 级别日志，不打印 ERROR
        log.debug("SSE 连接超时（正常行为）: {}", request.getRequestURI());
        // 不做任何响应处理，因为连接已经关闭
    }

    // ============ 修改所有方法，不要设置状态码为200 ============

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class})
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)  // 405
    public Result httpRequestMethodNotSuppertedException(HttpRequestMethodNotSupportedException ex,HttpServletResponse response,HttpServletRequest request) {
        // 让Spring自动设置正确的状态码
        if (this.active.equals("prd")) log.warn(LogMark.format("rest_exception", "httpRequestMethodNotSuppertedException"), ex);
        else log.error(ex.getMessage(), ex);
        // 返回405 Method Not Allowed，但Result中的code还是自定义的
        return Result.error(HttpStatus.METHOD_NOT_ALLOWED.value(), "请求方式错误");
    }

    @ExceptionHandler({NoHandlerFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)  // 404
    public Result noHandlerFoundException(NoHandlerFoundException ex,HttpServletRequest request,HttpServletResponse response) {
        if (request.getMethod().equalsIgnoreCase(RequestMethod.OPTIONS.name())) {
            return Result.ok(); // OPTIONS请求返回200
        } else {
            if (this.active.equals("prd"))
                log.warn(LogMark.format("rest_exception", "NoHandlerFoundException request url：{}，request method：{},message:{}"),
                        new Object[]{request.getRequestURI(), request.getMethod(), ex});
            else log.error(ex.getMessage(), ex);
            // 返回404 Not Found
            return Result.error(HttpStatus.NOT_FOUND.value(), "找不到请求路径");
        }
    }

    @ExceptionHandler({MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)  // 400
    public Result methodArgumentNotValidException(MethodArgumentNotValidException ex,HttpServletResponse response,HttpServletRequest request) {
        BindingResult bindingResult = ex.getBindingResult();
        StringBuilder sbf = new StringBuilder();
        for(ObjectError error : bindingResult.getAllErrors())
            sbf.append(error.getDefaultMessage()).append(";");
        if (this.active.equals("prd")) {
            log.warn(LogMark.format("rest_exception", "MethodArgumentNotValidException request url：{}，request method：{}"),
                    new Object[]{request.getRequestURI(), request.getMethod(), ex});
        } else log.error(ex.getMessage(), ex);
        // 返回400 Bad Request
        return Result.error(HttpStatus.BAD_REQUEST.value(), sbf.toString());
    }

    @ExceptionHandler({BusinessException.class})
    public Result msgException(BusinessException ex,HttpServletResponse response,HttpServletRequest request) {
        if (this.active.equals("prd")) {
            log.warn(LogMark.format("rest_exception", "BusinessException request url：{}，request method：{},message:{}"),
                    new Object[]{request.getRequestURI(), request.getMethod(), ex});
        } else log.warn(ex.getMessage(), ex);

        // 使用 ResponseEntity 确保HTTP状态码正确
        HttpStatus httpStatus = determineHttpStatusFromBusinessException(ex);
        Result result;
        if (ex.getActionType() != null) {
            result = Result.error(ex.getCode() == null ? httpStatus.value() : ex.getCode(),
                    ex.getMessage(),
                    ex.getActionType());
        } else {
            result = Result.error(ex.getCode() == null ? httpStatus.value() : ex.getCode(),
                    ex.getMessage());
        }
        return ResponseEntity.status(httpStatus).body(result).getBody();
    }

    @ExceptionHandler({NullPointerException.class})
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result nullPointerException(Exception ex, HttpServletResponse response, HttpServletRequest request) {
        if (this.active.equals("prd")) {
            log.error(LogMark.format("rest_exception", "NullPointerException request url：{}，request method：{},message:{}"), new Object[]{request.getRequestURI(), request.getMethod(), ex});
        } else log.error(ex.getMessage(), ex);
        // response.setStatus(HttpStatus.OK.value());

        return Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "歇一歇，再试试");
    }

    @ExceptionHandler({RemotingException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result dubboRemotingException(RemotingException remotingException, HttpServletResponse response, HttpServletRequest request) {
        if (this.active.equals("prd")) {
            log.error(LogMark.format("rest_exception", "DubboRemotingException request url：{}，request method：{},message:{}"), new Object[]{request.getRequestURI(), request.getMethod(), remotingException});
        } else log.error(remotingException.getMessage(), remotingException);
        // response.setStatus(HttpStatus.OK.value());
        return Result.error(900000000, "服务网络异常");
    }
    @ExceptionHandler({org.springframework.security.access.AccessDeniedException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)  // 403
    public Result handleAccessDeniedException(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("访问被拒绝: URL={}", request.getRequestURI());
        return Result.error(HttpStatus.FORBIDDEN.value(), "会话无权访问此资源");
    }

    @ExceptionHandler({Exception.class})
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) // 使用注解设置HTTP状态码
    public Result unknownException(Exception ex,HttpServletResponse response,HttpServletRequest request) {
        // ⭐ 忽略 SSE 相关的异常（已经在专门的 handler 处理）
        if (ex instanceof AsyncRequestTimeoutException) {
            log.debug("SSE 超时异常（已处理）: {}", request.getRequestURI());
            return null;  // 返回 null，Spring 会忽略
        }

        if (this.active.equals("prd")) {
            log.error(LogMark.format("rest_exception", "UnknownException request url：{}，request method：{},Message:{}"),
                    new Object[]{request.getRequestURI(), request.getMethod(), ex});
        } else log.error(ex.getMessage(), ex);
        String errorMessage = ex.getMessage();
        if (this.isError(errorMessage)) errorMessage = "系统繁忙，请稍后重试";
        // 返回500 Internal Server Error
        return Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), errorMessage);
    }

    /**
     * 根据业务异常确定HTTP状态码
     */
    private HttpStatus determineHttpStatusFromBusinessException(BusinessException ex) {
        if (ex.getCode() != null) {
            // 根据常见的HTTP状态码范围判断
            int code = ex.getCode();
            if (code >= 400 && code < 500) {
                // 400-499 客户端错误
                if (code == 401) return HttpStatus.UNAUTHORIZED;
                if (code == 403) return HttpStatus.FORBIDDEN;
                if (code == 404) return HttpStatus.NOT_FOUND;
                return HttpStatus.BAD_REQUEST;
            } else if (code >= 500 && code < 600) {
                // 500-599 服务器错误
                return HttpStatus.INTERNAL_SERVER_ERROR;
            }
        }
        // 默认返回400 Bad Request
        return HttpStatus.BAD_REQUEST;
    }

    private Boolean isError(String errorMessage) {
        return ObjectUtil.isNotEmpty(errorMessage) &&
                (errorMessage.contains("Exception") || errorMessage.length() > 50);
    }
}