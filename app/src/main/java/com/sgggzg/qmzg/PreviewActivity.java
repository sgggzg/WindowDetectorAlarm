package com.sgggzg.qmzg;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

public class PreviewActivity extends Activity {

    private static final int REQUEST_PICK_IMAGE = 100;

    private ImageView ivPreview;
    private RegionAdjustView regionView;
    private EditText etTop, etBottom, etLeft, etRight;
    private Button btnSelect, btnApply;

    private Bitmap currentBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        ivPreview = findViewById(R.id.ivPreviewImage);
        regionView = findViewById(R.id.regionView);
        etTop = findViewById(R.id.etTopPreview);
        etBottom = findViewById(R.id.etBottomPreview);
        etLeft = findViewById(R.id.etLeftPreview);
        etRight = findViewById(R.id.etRightPreview);
        btnSelect = findViewById(R.id.btnSelectImage);
        btnApply = findViewById(R.id.btnApply);

        float top = getIntent().getFloatExtra("top", 0.66f);
        float bottom = getIntent().getFloatExtra("bottom", 0.81f);
        float left = getIntent().getFloatExtra("left", 0f);
        float right = getIntent().getFloatExtra("right", 1f);

        etTop.setText(String.valueOf(top));
        etBottom.setText(String.valueOf(bottom));
        etLeft.setText(String.valueOf(left));
        etRight.setText(String.valueOf(right));

        regionView.setRegion(top, bottom);
        regionView.setRegionLR(left, right);

        // 拖动时同步到 EditText
        regionView.setOnRegionChangedListener((t, b) -> {
            etTop.setText(String.format("%.2f", t));
            etBottom.setText(String.format("%.2f", b));
            etLeft.setText(String.format("%.2f", regionView.getLeftRatio()));
            etRight.setText(String.format("%.2f", regionView.getRightRatio()));
        });

        // 【新增】等 imageView 完成布局后再计算图片实际显示矩形
        // 这样在没选图片时，regionView 也以整个 view 为基准
        ivPreview.getViewTreeObserver().addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    updateImageRect();
                }
            });

        btnSelect.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            startActivityForResult(intent, REQUEST_PICK_IMAGE);
        });

        btnApply.setOnClickListener(v -> {
            try {
                float newTop = Float.parseFloat(etTop.getText().toString());
                float newBottom = Float.parseFloat(etBottom.getText().toString());
                float newLeft = Float.parseFloat(etLeft.getText().toString());
                float newRight = Float.parseFloat(etRight.getText().toString());

                if (newBottom <= newTop || newRight <= newLeft) {
                    Toast.makeText(this, "边界无效：下必须大于上，右必须大于左", Toast.LENGTH_LONG).show();
                    return;
                }

                Intent result = new Intent();
                result.putExtra("top", newTop);
                result.putExtra("bottom", newBottom);
                result.putExtra("left", newLeft);
                result.putExtra("right", newRight);
                setResult(RESULT_OK, result);
                finish();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "输入格式错误", Toast.LENGTH_LONG).show();
            }
        });
    }

    // 【新增】计算 fitCenter 模式下图片实际显示区域
    private void updateImageRect() {
        Drawable d = ivPreview.getDrawable();
        if (d == null) {
            regionView.clearImageRect();
            return;
        }
        int vw = ivPreview.getWidth();
        int vh = ivPreview.getHeight();
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (vw == 0 || vh == 0 || dw == 0 || dh == 0) {
            regionView.clearImageRect();
            return;
        }
        float scale = Math.min((float) vw / dw, (float) vh / dh);
        float displayW = dw * scale;
        float displayH = dh * scale;
        float left = (vw - displayW) / 2f;
        float top = (vh - displayH) / 2f;
        regionView.setImageRect(new RectF(left, top, left + displayW, top + displayH));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            try {
                currentBitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                ivPreview.setImageBitmap(currentBitmap);

                // 【新增】图片设置后，等一次布局再更新图片显示矩形
                ivPreview.post(() -> updateImageRect());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
