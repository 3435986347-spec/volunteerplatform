package com.hengde.common.excel;

import com.alibaba.excel.EasyExcel;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Excel 导入导出工具，基于 EasyExcel。
 *
 * <p>后台批量导出（志愿者名单、报名名单等）与批量导入（小组数据等）共用本类。
 * 导出/导入的列由实体类上的 EasyExcel 注解（{@code @ExcelProperty} 等）描述，本类只负责读写。</p>
 *
 * @author hengde
 */
@Slf4j
public final class ExcelUtil {

    private ExcelUtil() {
    }

    /**
     * 导出数据到 HTTP 响应（浏览器直接下载 .xlsx）。
     *
     * @param response  HTTP 响应
     * @param fileName  文件名（不含扩展名，会自动加 .xlsx 并做 UTF-8 编码）
     * @param sheetName 工作表名
     * @param clazz     行数据类型（用其上的 EasyExcel 注解决定列）
     * @param data      行数据
     * @param <T>       行类型
     */
    public static <T> void export(HttpServletResponse response, String fileName, String sheetName,
                                  Class<T> clazz, List<T> data) {
        try {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + encoded + ".xlsx");
            EasyExcel.write(response.getOutputStream(), clazz).sheet(sheetName).doWrite(data);
        } catch (Exception e) {
            log.error("[Excel] 导出失败 fileName={}", fileName, e);
            // 把已经设好的 xlsx 响应头撤回来再抛。否则全局异常处理器要写的 Result JSON 与预设的 xlsx Content-Type
            // 对不上（HttpMessageNotWritableException），客户端拿到的是一个 HTTP 200、内容残缺的「xlsx」——
            // 打开报文件损坏，却看不到任何错误信息（2026-09-16 HTTP 全流程用例撞出来的）。
            // 已经提交（字节已刷出去）的响应撤不回，那种情况只能留给连接中断。
            if (!response.isCommitted()) {
                response.reset();
            }
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "Excel 导出失败");
        }
    }

    /**
     * 按<b>运行期才知道的表头</b>导出（问卷答卷：列随题目变化，没法写成带注解的行类）。
     * 失败处理与 {@link #export} 相同。
     *
     * @param head 表头，一列一个标题
     * @param rows 每行按表头顺序给出单元格文字
     */
    public static void exportTable(HttpServletResponse response, String fileName, String sheetName,
                                   List<String> head, List<List<String>> rows) {
        try {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + encoded + ".xlsx");
            List<List<String>> excelHead = head.stream().map(List::of).toList();
            EasyExcel.write(response.getOutputStream()).head(excelHead).sheet(sheetName).doWrite(rows);
        } catch (Exception e) {
            log.error("[Excel] 导出失败 fileName={}", fileName, e);
            if (!response.isCommitted()) {
                response.reset();
            }
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "Excel 导出失败");
        }
    }

    /**
     * 读取上传的 Excel 全部行。
     *
     * @param inputStream Excel 输入流（如 MultipartFile.getInputStream()）
     * @param clazz       行数据类型
     * @param <T>         行类型
     * @return 解析出的行列表
     */
    public static <T> List<T> read(InputStream inputStream, Class<T> clazz) {
        try {
            return EasyExcel.read(inputStream).head(clazz).sheet().doReadSync();
        } catch (Exception e) {
            log.error("[Excel] 导入解析失败", e);
            throw new BusinessException("Excel 解析失败，请检查文件格式");
        }
    }
}
