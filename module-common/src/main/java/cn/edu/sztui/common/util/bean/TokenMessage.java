package cn.edu.sztui.common.util.bean;

import lombok.Data;

import java.io.Serializable;

@Data
public class TokenMessage implements Serializable
{
    private static final long serialVersionUID = 1L;
    private String openId;
    private String unionId;
    private String sessionKey;
    /** 前端通过 header 传来的学校 cookies JSON（明文） */
    private String schoolCookiesJson;
}
