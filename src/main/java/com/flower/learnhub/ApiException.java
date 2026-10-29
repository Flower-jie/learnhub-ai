package com.flower.learnhub;

public final class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static ApiException missing() { return new ApiException(404, "NOT_FOUND", "知识库、文档、会话或笔记不存在"); }
    public static ApiException upstream() { return new ApiException(502, "AI_UNAVAILABLE", "AI 服务暂不可用，请检查配置或稍后重试"); }
    public static ApiException profileMismatch() {
        return new ApiException(409, "VECTOR_PROFILE_MISMATCH", "检索模式、模型或向量维度已变化，请新建知识库或删除全部文档后重新导入");
    }
}
