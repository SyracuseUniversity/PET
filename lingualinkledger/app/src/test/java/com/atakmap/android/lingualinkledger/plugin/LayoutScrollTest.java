package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * PL-37: a ScrollView works out how far it scrolls from its child's height
 * alone, so a margin on that child is never scrolled into view (on the S21+,
 * the main pane's last 12 dp). A ScrollView's child uses padding instead.
 */
public class LayoutScrollTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void noScrollViewChildHasAMargin() throws Exception {
        File[] layouts = layoutDir().listFiles((d, name) -> name.endsWith(".xml"));
        assertTrue("no layouts found", layouts != null && layouts.length > 0);
        List<String> found = new ArrayList<>();
        int scrollViews = 0;
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        for (File layout : layouts) {
            NodeList all = f.newDocumentBuilder().parse(layout).getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Element e = (Element) all.item(i);
                if (!e.getTagName().endsWith("ScrollView"))
                    continue;
                scrollViews++;
                for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (!(c instanceof Element))
                        continue;
                    NamedNodeMap attrs = c.getAttributes();
                    for (int a = 0; a < attrs.getLength(); a++) {
                        Node attr = attrs.item(a);
                        if (ANDROID.equals(attr.getNamespaceURI())
                                && attr.getLocalName().startsWith("layout_margin"))
                            found.add(layout.getName() + ": the child of " + e.getTagName()
                                    + " has android:" + attr.getLocalName() + "=\"" + attr.getNodeValue() + "\"");
                    }
                }
            }
        }
        assertTrue("no ScrollView found: is the layout folder right?", scrollViews > 0);
        if (!found.isEmpty())
            fail("use padding, not a margin, on a ScrollView's child:\n  " + String.join("\n  ", found));
    }

    /** app\src\main\res\layout, found by walking up from the test's working folder. */
    private static File layoutDir() {
        String rel = "src" + File.separator + "main" + File.separator + "res" + File.separator + "layout";
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null;
                d = d.getParentFile()) {
            for (File candidate : new File[] {new File(d, rel), new File(d, "app" + File.separator + rel)}) {
                if (new File(candidate, "main_layout.xml").isFile())
                    return candidate;
            }
        }
        throw new IllegalStateException(rel + " not found above " + System.getProperty("user.dir"));
    }
}
