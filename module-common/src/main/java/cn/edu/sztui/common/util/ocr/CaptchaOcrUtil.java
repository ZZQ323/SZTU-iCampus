package cn.edu.sztui.common.util.ocr;

import jakarta.annotation.Resource;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

@Service
public class CaptchaOcrUtil {

    private final Tesseract tesseract;

    public CaptchaOcrUtil() {
        tesseract = new Tesseract();
        // 设置语言包路径
        tesseract.setDatapath("D:\\DevEnvs\\Tesseract-OCR\\tessdata");
        // 设置识别语言
        tesseract.setLanguage("eng");
        // 验证码通常只有数字字母
        tesseract.setTessVariable("tessedit_char_whitelist", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz");
    }

    /**
     * 直接识别图片
     * @param imageBytes
     * @return
     * @throws TesseractException
     */
    public String recognize(byte[] imageBytes) throws TesseractException, IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        return tesseract.doOCR(image).trim();
    }
}
