package com.zeus.framework.ai.agent;

/** Ajan koşusunun neden durduğu. Çağıran bunu bir hata gibi ele almak isterse kendi kararıdır. */
public enum StopReason {
    /** Model araç çağırmayı bıraktı — normal bitiş. */
    MODEL_FINISHED,
    STEP_BUDGET,
    TOKEN_BUDGET,
    TIME_BUDGET,
    ERROR
}
