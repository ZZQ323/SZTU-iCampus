package cn.edu.sztui.common.util.auth;


import cn.edu.sztui.common.util.bean.TokenMessage;

public class  UserContext {
    private static final ThreadLocal<TokenMessage> CONTEXT_HOLDER = new ThreadLocal<>();

    public static void setContext(TokenMessage context) {
        CONTEXT_HOLDER.set(context);
    }

    public static TokenMessage getContext() {
        return CONTEXT_HOLDER.get();
    }

    public static void clear() {
        CONTEXT_HOLDER.remove();
    }
}
