package com.hengde.api.controller;

import com.hengde.api.vo.FileUploadVO;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.oss.OssProperties;
import com.hengde.common.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VolunteerFileUploadControllerTest {

    @Test
    void uploadProfileImage_acceptsGroupLogoDir() {
        VolunteerFileUploadController controller = new VolunteerFileUploadController();
        AtomicReference<String> uploadedDir = new AtomicReference<>();
        controller.setFileStorageService(new FileStorageService() {
            @Override
            public String upload(MultipartFile file, String dir) {
                uploadedDir.set(dir);
                return "https://cdn.example.com/" + dir + "/logo.png";
            }

            @Override
            public String upload(byte[] data, String objectName, String contentType) {
                return "https://cdn.example.com/" + objectName;
            }

            @Override
            public void delete(String objectName) {
            }

            /* 私有对象那几个方法与本用例（公开图片上传）无关，但接口扩展后必须实现，
               否则整个 api 模块 test-compile 失败、工作树构建不过去。
               这里刻意抛异常而不是返回假值：本用例若真走到私有对象路径，说明被测代码
               把公开上传误接到了私有通道上，那应当立刻炸出来而不是悄悄通过。 */
            @Override
            public String uploadPrivate(byte[] data, String objectKey, String contentType) {
                throw new UnsupportedOperationException("公开图片上传不该走私有对象通道");
            }

            @Override
            public byte[] download(String objectKey) {
                throw new UnsupportedOperationException("公开图片上传不该回读对象内容");
            }

            @Override
            public String presignGet(String objectKey, Duration ttl) {
                throw new UnsupportedOperationException("公开图片上传不该需要签名 URL");
            }
        });
        OssProperties properties = new OssProperties();
        properties.setMaxFileSize(1024 * 1024);
        controller.setOssProperties(properties);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "logo.png",
                "image/png",
                pngBytes());

        Result<FileUploadVO> result = controller.uploadProfileImage(file, "group");

        assertEquals("group", uploadedDir.get());
        assertEquals("https://cdn.example.com/group/logo.png", result.getData().getUrl());
        assertEquals("logo.png", result.getData().getName());
    }

    private static byte[] pngBytes() {
        byte[] header = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        };
        byte[] tail = "minimal".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[header.length + tail.length];
        System.arraycopy(header, 0, bytes, 0, header.length);
        System.arraycopy(tail, 0, bytes, header.length, tail.length);
        return bytes;
    }
}
