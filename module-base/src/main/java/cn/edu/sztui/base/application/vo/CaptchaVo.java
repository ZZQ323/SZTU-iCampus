package cn.edu.sztui.base.application.vo;

import cn.edu.sztui.common.util.smarthttp.dto.SmartCookie;
import lombok.Data;
import java.util.List;

@Data
public class CaptchaVo {
    List<SmartCookie> cookies;
    String captchaBase64;
}
