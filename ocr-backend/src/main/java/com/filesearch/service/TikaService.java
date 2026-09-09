package com.filesearch.service;

import java.io.File;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TikaService {

	private final Tika tika;

	public String extract(String filePath) throws Exception {

		File file = new File(filePath);

		if (!file.exists()) {
			throw new IllegalArgumentException("파일이 존재하지 않습니다: " + filePath);
		}

		log.info("[TIKA] 파일 텍스트 추출: {}", file.getAbsolutePath());

		// 실제 JVM에서 로딩된 PDF2XHTML 클래스의 JAR 위치 확인
		try {
			Class<?> clazz = Class.forName("org.apache.tika.parser.pdf.PDF2XHTML");
			System.out.println(
					"[DEBUG] PDF2XHTML loaded from: " + clazz.getProtectionDomain().getCodeSource().getLocation());
			for (var method : clazz.getDeclaredMethods()) {
			    if (method.getName().contains("IgnoreContentStream")) {
			        System.out.println("[DEBUG] FOUND METHOD = " + method);
			    }
			}
			
			Class<?> configClass =
			        Class.forName("org.apache.tika.parser.pdf.PDFParserConfig");

			System.out.println(
			    "[DEBUG] PDFParserConfig loaded from = " +
			    configClass.getProtectionDomain()
			               .getCodeSource()
			               .getLocation()
			);
			
			
			
			Class<?> pdfTextStripperClass =
			        Class.forName("org.apache.pdfbox.text.PDFTextStripper");

			System.out.println(
			        "[DEBUG] PDFTextStripper loaded from = " +
			        pdfTextStripperClass
			                .getProtectionDomain()
			                .getCodeSource()
			                .getLocation()
			);

			try {
			    System.out.println(
			        "[DEBUG] PDFTextStripper method = " +
			        pdfTextStripperClass.getMethod(
			            "setIgnoreContentStreamSpaceGlyphs",
			            boolean.class
			        )
			    );
			} catch (NoSuchMethodException e) {
			    System.out.println(
			        "[DEBUG] PDFTextStripper DOES NOT HAVE setIgnoreContentStreamSpaceGlyphs"
			    );
			}
		} catch (Exception e) {
			System.out.println("[DEBUG] PDF2XHTML 클래스를 로딩하지 못했습니다.");
			e.printStackTrace();
		}

		return tika.parseToString(file);
	}
}