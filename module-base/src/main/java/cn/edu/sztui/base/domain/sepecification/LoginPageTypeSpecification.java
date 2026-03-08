package cn.edu.sztui.base.domain.sepecification;

import cn.edu.sztui.base.domain.model.login.LoginType;
import cn.edu.sztui.common.ddd.AbstractSpecification;

public class LoginPageTypeSpecification extends AbstractSpecification<LoginType> {

    @Override
    public boolean isSatisfiedBy(LoginType var1) {
        return false;
    }
}
