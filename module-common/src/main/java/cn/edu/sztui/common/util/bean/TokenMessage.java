package cn.edu.sztui.common.util.bean;

import lombok.Data;

import java.io.Serializable;

@Data
public class TokenMessage implements Serializable
{
    private static final long serialVersionUID = 1L;
    /** 用户学号（登录后前端通过 header 传来） */
    private String userId;
    /** 前端通过 header 传来的学校 cookies JSON（明文） */
    private String schoolCookiesJson;
}
