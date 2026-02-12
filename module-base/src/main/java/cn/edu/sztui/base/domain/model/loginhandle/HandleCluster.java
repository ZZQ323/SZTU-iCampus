package cn.edu.sztui.base.domain.model.loginhandle;

import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class HandleCluster {
    private final Map<LoginType, LoginHandle> handleMap = new EnumMap<>(LoginType.class);

    @Autowired(required = false)  // 添加 required = false，避免没有处理器时启动失败
    public HandleCluster(List<LoginHandle> loginHandles) {
        if (loginHandles == null || loginHandles.isEmpty()) {
            log.warn("未找到任何 LoginHandle 实现类");
            return;
        }

        for (LoginHandle handle : loginHandles) {
            LoginType type = handle.getLoginType();
            if (type == null) {
                log.error("LoginHandle {} 返回的 LoginType 为 null，跳过",
                        handle.getClass().getSimpleName());
                continue;
            }

            if (handleMap.containsKey(type)) {
                log.warn("重复的 LoginType: {}，已存在: {}，新的: {}",
                        type,
                        handleMap.get(type).getClass().getSimpleName(),
                        handle.getClass().getSimpleName());
            }

            handleMap.put(type, handle);
            log.info("已注册 LoginHandle: {} -> {}", type, handle.getClass().getSimpleName());
        }

        log.info("共加载 {} 个登录处理器: {}", handleMap.size(), handleMap.keySet());
    }

    public LoginHandle getSpringLoginHandle(LoginType type) {
        if (type == null) {
            throw new IllegalArgumentException("LoginType 不能为 null");
        }

        LoginHandle handle = handleMap.get(type);
        if (handle == null) {
            throw new BusinessException(SysReturnCode.BASE_PROXY.getCode(), "不支持的登录类型: " + type , ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode() );
        }
        return handle;
    }

    public List<LoginType> getSupportedLoginTypes() {
        return new ArrayList<>(handleMap.keySet());
    }

    public static LoginHandle createNewLoginHandle(LoginType type) {
        return LoginHandle.createLoginHandle(type);
    }
}
