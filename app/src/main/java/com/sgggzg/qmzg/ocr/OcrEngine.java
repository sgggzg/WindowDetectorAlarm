package com.sgggzg.qmzg.ocr;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * OCR 引擎抽象。
 *
 * <p>界面和服务层只依赖这个接口，以后换 PaddleOCR / 其他模型时不用改业务代码。</p>
 */
public interface OcrEngine {

    interface InitCallback {
        void onSuccess();

        void onFailure(String message);
    }

    void init(Context context, InitCallback callback);

    OcrResult recognize(Bitmap bitmap);

    void release();

    boolean isReady();

    String displayName();
}
