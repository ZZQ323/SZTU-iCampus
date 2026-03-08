package cn.edu.sztui.base.application.vo;

import cn.edu.sztui.base.domain.model.login.LoginType;
import cn.edu.sztui.common.util.enums.SysChannelEnum;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class LoginResultsVo
{
    @JsonProperty("logined")
    private boolean isLogined;
    private String wxId;
    private String userId;
    private String realName;
    private String gender;
    private String coments;
    // private String phone;
    private String schoolName;
    private String avatarURL;
    private SysChannelEnum sysChannel;
    private List<LoginType> loginTypes;
}
