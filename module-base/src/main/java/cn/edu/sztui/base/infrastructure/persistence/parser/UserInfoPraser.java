package cn.edu.sztui.base.infrastructure.persistence.parser;

import cn.edu.sztui.base.application.vo.LoginResultsVo;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j

public class UserInfoPraser
{
    /**
     * 使用正则表达式作为备选方案
     */
    public static void extractByRegex(LoginResultsVo ret,String htmlContent) {
        // 提取姓名
        Pattern namePattern = Pattern.compile("姓名[：:]([^<>\n]+)");
        Matcher nameMatcher = namePattern.matcher(htmlContent);
        if (nameMatcher.find()) {
            String userName = nameMatcher.group(1).trim();
            ret.setRealName(userName);
        }

        // 提取工号
        Pattern idPattern = Pattern.compile("工号[：:]([^<>\n]+)");
        Matcher idMatcher = idPattern.matcher(htmlContent);
        if (idMatcher.find()) {
            String userId = idMatcher.group(1).trim();
            ret.setUserId(userId);
            // https://home-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/opt/bmportal/attachments/photo/202200202104.png
            ret.setAvatarURL("https://home-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/opt/bmportal/attachments/photo/"+userId+".png");
        }

        // 提取部门
        Pattern deptPattern = Pattern.compile("部门[：:]([^<>\n]+)");
        Matcher deptMatcher = deptPattern.matcher(htmlContent);
        if (deptMatcher.find()) {
            String department = deptMatcher.group(1).trim();
            ret.setSchoolName(department);
        }

        // 提取性别
        Pattern genderPattern = Pattern.compile("性别[：:]([^<>\n]+)");
        Matcher genderMatcher = genderPattern.matcher(htmlContent);
        if (genderMatcher.find()) {
            String gender = genderMatcher.group(1).trim();
            ret.setGender(gender);
        }
        log.info("【返回前】解析到用户信息: userId={}, realName={}", ret.getUserId(), ret.getRealName());
    }
}
