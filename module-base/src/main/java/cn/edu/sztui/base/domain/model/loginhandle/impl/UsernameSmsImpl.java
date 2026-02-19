package cn.edu.sztui.base.domain.model.loginhandle.impl;

import cn.edu.sztui.base.application.dto.command.LoginRequestCommand;
import cn.edu.sztui.base.domain.model.loginhandle.LoginHandle;
import cn.edu.sztui.base.domain.model.loginhandle.LoginType;
import cn.edu.sztui.base.infrastructure.util.praser.URLPraser;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import static cn.edu.sztui.base.domain.model.SchoolAPIs.*;

@Slf4j
@Service
public class UsernameSmsImpl implements LoginHandle {

    @Override
    public LoginType getLoginType() {
        return LoginType.SMS;
    }

    @Override
    public APIResponse loginVerification(BrowserContext context, LoginRequestCommand cmd) {

        FormData formData = FormData.create();
        formData.set("j_username", cmd.getUserId());
        formData.set("sms_checkcode", cmd.getSmsCode());
        formData.set("j_checkcode", "验证码");
        formData.set("op", "login");
        formData.set("spAuthChainCode", spAuthChainCodeSMS);

        APIRequestContext req = context.request();
        APIResponse ajaxRes = req.post(loginURL+ "?sf_request_type=ajax",
            RequestOptions.create()
                .setForm(formData)
                .setHeader("X-Requested-With", "XMLHttpRequest")
                .setHeader("Referer", gatewayFirstEndURL)
                .setHeader("Origin", URLPraser.extractOrigin(loginURL))  // 注意这里应该是loginURL的origin
                .setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0")
                .setHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .setHeader("Accept", "*/*")
        );
        return ajaxRes;
    }

    @Override
    public APIResponse loginRedirect(BrowserContext context, LoginRequestCommand cmd) {
        FormData formData = FormData.create();
        formData.set("j_username", cmd.getUserId());
        formData.set("sms_checkcode", cmd.getSmsCode());
        formData.set("j_checkcode", "验证码");
        formData.set("op", "login");
        formData.set("spAuthChainCode", spAuthChainCodeSMS);

        APIRequestContext req = context.request();
        APIResponse formRes = req.post(
            A4tLoginSMSFormActionURL,
            RequestOptions.create()
                .setForm(formData)
                .setHeader("Referer", gatewayFirstEndURL)
                .setHeader("X-Requested-With", "XMLHttpRequest")
                .setHeader("Origin", URLPraser.extractOrigin(loginURL))
                .setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0")
        );
        
        return formRes;
    }
}
