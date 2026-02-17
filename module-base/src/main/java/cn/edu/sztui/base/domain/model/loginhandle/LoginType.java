package cn.edu.sztui.base.domain.model.loginhandle;

import com.fasterxml.jackson.annotation.JsonValue;

public enum LoginType {
    SMS("短信验证码登录"),
    PASSWORD("账号密码登录"),
    OTP("动态口令登录");

    private final String chineseName;

    LoginType(String chineseName) {this.chineseName = chineseName;}

    @JsonValue  // 关键：序列化时使用这个方法返回值
    public String getChineseName() {return chineseName;}

    // 也可以加个get方法获取枚举常量
    public String getCode() {return this.name();}
}
