package cn.edu.sztui.base.domain.model.login;

public interface SchoolAPIs
{
    // 网关验证 URL
    final String gatewayStartURL ="https://home.sztu.edu.cn/bmportal";
    final String gatewayFirstURL ="https://auth-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/idp/authcenter/ActionAuthChain?entityId=webvpn";
    final String gatewaySecondURL ="https://auth-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/idp/authcenter/ActionAuthChain?entityId=home";
    final String gatewaySmsURL ="https://auth-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/idp/sendSMSCheckCode.do";
    final String gatewayLoginSubmitURL ="https://auth-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/idp/authcenter/ActionAuthChain";
    final String A4tLoginSMSRedirectURL ="https://auth-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/idp/AuthnEngine?currentAuth=urn_oasis_names_tc_SAML_2.0_ac_classes_SMSUsernamePassword";
    final String A4tLoginPASSWORDRedirectURL ="https://auth-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/idp/AuthnEngine?currentAuth=urn_oasis_names_tc_SAML_2.0_ac_classes_BAMUsernamePassword";
    final String logoutSubmitURL ="https://home-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/bmportal/logout.portal";
    // 登录标识
    final String spAuthChainCodeSMS ="3c21e7d55f6449df85e8cebc30518464";
    final String spAuthChainCodePASSWORD ="cc2fdbc3599b48a69d5c82a665256b6b";

    // 教务系统 URL
    final String AcdmGatewayURL ="https://jwxt-sztu-edu-cn.webvpn.sztu.edu.cn:8118/";
    final String AcdmSwitchPort = "https://jwxt-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/jsxsd/framework/xsrkxz.htmlx";
    final String AcdmAdminSysURL = "https://jwxt-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/jsxsd/framework/xsMain";;
    final String acdemAdminSysGatewayStartURL = "https://home-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/bmportal/index.portal";
    final String AcdmScheduleTableURL = "https://jwxt-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/jsxsd/xskb/xskb_list.do";
}
