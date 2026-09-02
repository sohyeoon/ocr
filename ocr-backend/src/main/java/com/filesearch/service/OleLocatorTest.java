package com.filesearch.service;

import java.util.List;
import java.util.ArrayList;

import com.sun.star.beans.Property;
import com.sun.star.beans.PropertyValue;
import com.sun.star.beans.XPropertySet;
import com.sun.star.beans.XPropertySetInfo;
import com.sun.star.bridge.XUnoUrlResolver;
import com.sun.star.comp.helper.Bootstrap;
import com.sun.star.container.XNameAccess;
import com.sun.star.frame.XComponentLoader;
import com.sun.star.frame.XModel;
import com.sun.star.lang.XComponent;
import com.sun.star.lang.XMultiComponentFactory;
import com.sun.star.text.XPageCursor;
import com.sun.star.text.XTextContent;
import com.sun.star.text.XTextEmbeddedObjectsSupplier;
import com.sun.star.text.XTextRange;
import com.sun.star.text.XTextViewCursor;
import com.sun.star.text.XTextViewCursorSupplier;
import com.sun.star.uno.UnoRuntime;
import com.sun.star.uno.XComponentContext;
import com.sun.star.util.XCloseable;

public class OleLocatorTest {

    public static void main(String[] args) throws Exception {

        // ============================================================
        // 1. 로컬 UNO Context 생성
        // ============================================================

        XComponentContext localContext =
                Bootstrap.createInitialComponentContext(null);

        XMultiComponentFactory localFactory =
                localContext.getServiceManager();


        // ============================================================
        // 2. LibreOffice UNO Listener 접속
        // ============================================================

        Object resolverObj =
                localFactory.createInstanceWithContext(
                        "com.sun.star.bridge.UnoUrlResolver",
                        localContext
                );

        XUnoUrlResolver resolver =
                UnoRuntime.queryInterface(
                        XUnoUrlResolver.class,
                        resolverObj
                );

        Object remoteContextObj =
                resolver.resolve(
                        "uno:socket,host=172.21.0.219,port=2002;" +
                        "urp;StarOffice.ComponentContext"
                );

        XComponentContext officeContext =
                UnoRuntime.queryInterface(
                        XComponentContext.class,
                        remoteContextObj
                );

        XMultiComponentFactory officeFactory =
                officeContext.getServiceManager();


        // ============================================================
        // 3. LibreOffice Desktop 생성
        // ============================================================

        Object desktopObj =
                officeFactory.createInstanceWithContext(
                        "com.sun.star.frame.Desktop",
                        officeContext
                );

        XComponentLoader loader =
                UnoRuntime.queryInterface(
                        XComponentLoader.class,
                        desktopObj
                );

        System.out.println("[OK] LibreOffice 연결 성공");


        // ============================================================
        // 4. DOCX 파일 열기
        // ============================================================

        String fileUrl =
                "file:///data/efiletest.docx";

        PropertyValue hidden =
                new PropertyValue();

        hidden.Name = "Hidden";

        // ★ 레이아웃 확인을 위해 일단 화면 표시
        hidden.Value = Boolean.FALSE;

        XComponent doc =
                loader.loadComponentFromURL(
                        fileUrl,
                        "_blank",
                        0,
                        new PropertyValue[]{hidden}
                );

        System.out.println(
                "[OK] 문서 로드: " + fileUrl
        );


        try {

            // ========================================================
            // 5. Writer Model
            // ========================================================

            XModel model =
                    UnoRuntime.queryInterface(
                            XModel.class,
                            doc
                    );


            // ========================================================
            // 6. View Cursor
            // ========================================================

            XTextViewCursorSupplier cursorSupplier =
                    UnoRuntime.queryInterface(
                            XTextViewCursorSupplier.class,
                            model.getCurrentController()
                    );

            XTextViewCursor viewCursor =
                    cursorSupplier.getViewCursor();


            // ========================================================
            // 7. Page Cursor
            // ========================================================

            XPageCursor pageCursor =
                    UnoRuntime.queryInterface(
                            XPageCursor.class,
                            viewCursor
                    );


            // ========================================================
            // 8. 페이지 레이아웃 강제 계산
            // ========================================================

            pageCursor.jumpToLastPage();

            int totalPages =
                    pageCursor.getPage();

            System.out.println(
                    "[DEBUG] 전체 페이지 수 = " +
                    totalPages
            );

            pageCursor.jumpToFirstPage();


            // ========================================================
            // 9. OLE Embedded Object Supplier
            // ========================================================

            XTextEmbeddedObjectsSupplier supplier =
                    UnoRuntime.queryInterface(
                            XTextEmbeddedObjectsSupplier.class,
                            doc
                    );

            if (supplier == null) {

                System.out.println(
                        "[ERROR] XTextEmbeddedObjectsSupplier 변환 실패"
                );

                return;
            }


            // ========================================================
            // 10. Embedded Object 목록
            // ========================================================

            XNameAccess embeddedObjects =
                    supplier.getEmbeddedObjects();

            String[] names =
                    embeddedObjects.getElementNames();

            System.out.println(
                    "[INFO] OLE 개수 = " +
                    names.length
            );


            // Anchor 비교를 위한 리스트
            List<XTextRange> anchors =
                    new ArrayList<>();


            // ========================================================
            // 11. OLE 하나씩 분석
            // ========================================================

            int index = 0;

            for (String name : names) {

                index++;

                System.out.println();
                System.out.println(
                        "================================================"
                );

                System.out.println(
                        "========== OLE #" +
                        index +
                        " =========="
                );

                System.out.println(
                        "================================================"
                );


                // ----------------------------------------------------
                // OLE Object
                // ----------------------------------------------------

                Object embedObj =
                        embeddedObjects.getByName(name);


                // ----------------------------------------------------
                // PropertySet
                // ----------------------------------------------------

                XPropertySet props =
                        UnoRuntime.queryInterface(
                                XPropertySet.class,
                                embedObj
                        );

                if (props == null) {

                    System.out.println(
                            "[WARN] XPropertySet 변환 실패"
                    );

                    continue;
                }


                // ----------------------------------------------------
                // CLSID
                // ----------------------------------------------------

                try {

                    Object clsid =
                            props.getPropertyValue("CLSID");

                    System.out.println(
                            "CLSID = " +
                            clsid
                    );

                } catch (Exception e) {

                    System.out.println(
                            "CLSID = N/A"
                    );
                }


                // ====================================================
                // ★ AnchorType 확인
                // ====================================================

                try {

                    Object anchorType =
                            props.getPropertyValue(
                                    "AnchorType"
                            );

                    System.out.println(
                            "AnchorType class = " +
                            anchorType.getClass().getName()
                    );

                    System.out.println(
                            "AnchorType = " +
                            anchorType.toString()
                    );

                } catch (Exception e) {

                    System.out.println(
                            "AnchorType = N/A"
                    );
                }


                // ====================================================
                // ★ 주요 위치 관련 Property 확인
                // ====================================================

                String[] propertyNames = {

                        "AnchorType",
                        "AnchorPageNo",
                        "VertOrient",

                        "HoriOrient",

                        "VertOrientPosition",

                        "HoriOrientPosition",

                        "Width",

                        "Height"
                };
                
                try {
                    Object value =
                            props.getPropertyValue("AnchorPageNo");

                    int pageNumber =
                            ((Number) value).intValue();

                    System.out.println(
                            "[RESULT] OLE #" +
                            index +
                            " pageNumber = " +
                            pageNumber
                    );

                } catch (Exception e) {

                    System.out.println(
                            "[ERROR] AnchorPageNo 조회 실패: " +
                            e.getMessage()
                    );
                }


                for (String propertyName :
                        propertyNames) {

                    try {

                        Object value =
                                props.getPropertyValue(
                                        propertyName
                                );

                        System.out.println(
                                propertyName +
                                " = " +
                                value
                        );

                    } catch (Exception e) {

                        System.out.println(
                                propertyName +
                                " = N/A"
                        );
                    }
                }


                // ====================================================
                // ★ 이 OLE가 지원하는 전체 Property 목록
                // ====================================================

                System.out.println();
                System.out.println(
                        "[DEBUG] OLE 지원 Property 목록"
                );

                try {

                    XPropertySetInfo propertySetInfo =
                            props.getPropertySetInfo();

                    Property[] properties =
                            propertySetInfo.getProperties();

                    for (Property property :
                            properties) {

                        System.out.println(
                                "  - " +
                                property.Name
                        );
                    }

                } catch (Exception e) {

                    System.out.println(
                            "[WARN] Property 목록 조회 실패: " +
                            e.getMessage()
                    );
                }


                // ====================================================
                // 12. XTextContent
                // ====================================================

                XTextContent textContent =
                        UnoRuntime.queryInterface(
                                XTextContent.class,
                                embedObj
                        );

                if (textContent == null) {

                    System.out.println(
                            "[WARN] XTextContent 변환 실패"
                    );

                    continue;
                }


                // ====================================================
                // 13. Anchor
                // ====================================================

                XTextRange anchor =
                        textContent.getAnchor();

                if (anchor == null) {

                    System.out.println(
                            "[WARN] Anchor 없음"
                    );

                    continue;
                }

                anchors.add(anchor);


                // ====================================================
                // 14. Anchor Text
                // ====================================================

                String anchorText = "";

                try {

                    anchorText =
                            anchor.getString();

                } catch (Exception ignored) {
                }

                System.out.println(
                        "anchor text = [" +
                        anchorText +
                        "]"
                );


                // ====================================================
                // 15. Anchor → Page
                // ====================================================

                try {

                    viewCursor.gotoRange(
                            anchor,
                            false
                    );

                    // 레이아웃 계산 시간 확보
                    Thread.sleep(100);

                    short anchorPage =
                            pageCursor.getPage();

                    System.out.println(
                            "anchor page = " +
                            anchorPage
                    );

                } catch (Exception e) {

                    System.out.println(
                            "[WARN] Anchor Page 계산 실패: " +
                            e.getMessage()
                    );
                }


                // ====================================================
                // 16. Anchor 객체 정보
                // ====================================================

                try {

                    System.out.println(
                            "anchor start = " +
                            anchor.getStart()
                    );

                    System.out.println(
                            "anchor end = " +
                            anchor.getEnd()
                    );

                } catch (Exception e) {

                    System.out.println(
                            "[WARN] Anchor 범위 출력 실패"
                    );
                }
            }


            // ========================================================
            // 17. OLE Anchor 순서 비교
            // ========================================================

            if (anchors.size() >= 2) {

                System.out.println();
                System.out.println(
                        "================================================"
                );

                System.out.println(
                        "[DEBUG] OLE Anchor 순서 비교"
                );

                System.out.println(
                        "================================================"
                );

                try {

                    XTextRange firstAnchor =
                            anchors.get(0);

                    XTextRange secondAnchor =
                            anchors.get(1);

                    com.sun.star.text.XTextRangeCompare rangeCompare =
                            UnoRuntime.queryInterface(
                                    com.sun.star.text.XTextRangeCompare.class,
                                    firstAnchor.getText()
                            );

                    if (rangeCompare != null) {

                        short result =
                                rangeCompare.compareRegionStarts(
                                        firstAnchor,
                                        secondAnchor
                                );

                        System.out.println(
                                "OLE #1 Anchor vs OLE #2 Anchor = " +
                                result
                        );

                        /*
                         * 결과 해석
                         *
                         * 음수 : OLE #1 Anchor가 앞
                         * 0    : 같은 위치
                         * 양수 : OLE #1 Anchor가 뒤
                         */

                    } else {

                        System.out.println(
                                "[WARN] XTextRangeCompare 변환 실패"
                        );
                    }

                } catch (Exception e) {

                    System.out.println(
                            "[WARN] Anchor 비교 실패: " +
                            e.getMessage()
                    );
                }
            }


        } finally {

            // ========================================================
            // 18. 문서 닫기
            // ========================================================

            XCloseable closeable =
                    UnoRuntime.queryInterface(
                            XCloseable.class,
                            doc
                    );

            if (closeable != null) {

                closeable.close(false);

            } else {

                doc.dispose();
            }

            System.out.println(
                    "[OK] 문서 닫음"
            );
        }
    }
}