package com.example.XML2TML.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.springframework.stereotype.Service;

@Service
public class XmlLayoutService {
    public record FieldRow(
            int from, int to, int length, String dataType,
            String description, String value, String nodeType,
            String branch, String format, int repeats, int recordLength
    ) {}
    public record SheetLayout(
            String name, String section, String branch,
            String condition, List<FieldRow> rows
    ) {}
    public record Layout(
            String groupId, String groupName,
            Map<String, String> groupAttributes,
            List<SheetLayout> sheets, List<String> xmlLines
    ) {}

    private final Pattern fieldPattern =
            Pattern.compile("^([9X])(?:\\((\\d+)\\))?(?:V(\\d+))?S?$");

    public Layout parse(InputStream input) throws Exception {
        byte[] bytes = input.readAllBytes();
        String xml = new String(bytes, StandardCharsets.UTF_8);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        Element group = doc.getDocumentElement();
        if (!"GROUP".equals(group.getTagName())) throw new IllegalArgumentException("Root element must be GROUP.");

        Map<String, String> attributes = attributes(group);
        List<SheetLayout> sheets = new ArrayList<>();

        for (String sectionName : List.of("TX", "RX")){
            Element section = directChild(group, sectionName);
            if (section == null) {
                continue;
            }
            processSection(sectionName, section, sheets);
        }

        List<String> xmlLines = xml.lines().toList();

        return new Layout(
            group.getAttribute("ID"),
            group.getAttribute("Name"),
            attributes,
            List.copyOf(sheets),
            xmlLines
            );
    }

    private record Variant(String label, List<Element> nodes) {}

    private record Context(String section, String branch, List<SheetLayout> sheets,
                           String parentName, String suffix) {}

    private void processSection(String sectionName, Element section, List<SheetLayout> sheets) {
        List<Element> nodes = children(section);
        int selectIndex = indexOfSelect(nodes);

        if (selectIndex < 0) {
            for (Variant variant : variants(nodes)) {
                String name = join(sectionName, variant.label());
                List<FieldRow> rows = new ArrayList<>();
                layout(variant.nodes(), rows, 1,
                        new Context(sectionName, variant.label(), sheets, name, ""));
                if (!hasFields(rows)) continue;
                sheets.add(new SheetLayout(
                        name, sectionName, variant.label(), "", List.copyOf(rows)));
            }
            return;
        }

        Element select = nodes.get(selectIndex);
        List<Element> prefix = variants(nodes.subList(0, selectIndex)).get(0).nodes();
        List<Element> suffix = variants(
                nodes.subList(selectIndex + 1, nodes.size())).get(0).nodes();


        // Every main case is sent on its own, so each one starts again right after
        // the shared fields before the SELECT (e.g. formNo = 1, so each case starts
        // at 2; with no such fields each case starts at 1).
        int casePosition = 1 + length(prefix);

        for (Element branch : children(select)) {
            if (!isCase(branch)) continue;

            String branchName = branchName(branch);

            for (Variant variant : variants(children(branch))) {
                String label = join(branchName, variant.label());
                String sheetName = sectionName + " " + label;
                Context ctx = new Context(sectionName, label, sheets, sheetName, "");

                List<FieldRow> rows = new ArrayList<>();
                layout(prefix, rows, 1, ctx);
                int caseStartRow = rows.size();
                int end = layout(variant.nodes(), rows, casePosition, ctx);
                List<FieldRow> caseRows = new ArrayList<>(rows.subList(caseStartRow, rows.size()));
                layout(suffix, rows, end, ctx);

                // A case that only holds a collection (e.g. CASE 1 -> Collection1)
                // is shown by the collection's own sheet(s); no extra sheet for it.
                if (!hasFields(caseRows)) continue;

                String details = "SELECT switch=" + switchName(select)
                        + "; " + label + "; starts at " + casePosition;
                sheets.add(new SheetLayout(
                        sheetName, sectionName, label, details, List.copyOf(rows)));
            }
        }
    }


    private List<Variant> variants(List<Element> nodes) {
        List<Variant> result = List.of(new Variant("", List.of()));

        for (Element node : nodes) {
            String tag = node.getTagName();
            List<Variant> parts;

            if ("SELECT".equals(tag)) {
                parts = new ArrayList<>();
                for (Element branch : children(node)) {
                    if (!isCase(branch)) continue;
                    for (Variant inner : variants(children(branch))) {
                        parts.add(new Variant(
                                join(branchName(branch), inner.label()), inner.nodes()));
                    }
                }
                if (parts.isEmpty()) continue;
            } else if ("FIELD".equals(tag) || "COLLECTION".equals(tag)) {
                parts = List.of(new Variant("", List.of(node)));
            } else {
                parts = variants(children(node));
            }

            List<Variant> next = new ArrayList<>();
            for (Variant before : result) {
                for (Variant part : parts) {
                    List<Element> combined = new ArrayList<>(before.nodes());
                    combined.addAll(part.nodes());
                    next.add(new Variant(join(before.label(), part.label()), combined));
                }
            }
            result = next;
        }
        return result;
    }

    private int length(List<Element> nodes) {
        return layout(nodes, new ArrayList<>(), 1, new Context("", "", null, "", "")) - 1;
    }

    private int layout(List<Element> nodes, List<FieldRow> rows, int position, Context ctx) {
        for (Element node : nodes) {
            String tag = node.getTagName();

            if ("FIELD".equals(tag)) {

                String format = node.getAttribute("Format");
                int length = formatLength(format);

                rows.add(new FieldRow(
                        position,
                        position + length - 1,
                        length,
                        dataType(format),
                        node.getAttribute("ID") + ctx.suffix(),
                        "",
                        "FIELD",
                        ctx.branch(),
                        format,
                        1,
                        0
                ));
                position += length;

            } else if ("COLLECTION".equals(tag)) {
                position = layoutCollection(node, rows, position, ctx);

            } else if (!"SELECT".equals(tag)) {
                // Wrapper element. (SELECTs were already expanded by variants().)
                position = layout(children(node), rows, position, ctx);
            }
        }
        return position;
    }


    /**
     * Positions continue from {@code position} (the length summed so far):
     * <ul>
     *   <li>With a SELECT switch inside: every sub-case is one record that starts at
     *       the same position, e.g. CASE 01 = 89..201 and CASE 0200 = 89..186.</li>
     *   <li>Without a switch: the record repeats NumSubParts times, each one right
     *       after the previous, e.g. 253..438, 439..624, ... (record length x NumSubParts).</li>
     * </ul>
     */
    private int layoutCollection(Element node, List<FieldRow> rows, int position, Context ctx) {
        String collectionId = node.getAttribute("ID");
        int numSubParts = positiveInt(
                node.getAttribute("NumSubParts"), 1, "NumSubParts on " + collectionId);

        boolean hasSwitch = containsSelect(children(node));
        int repeats = hasSwitch ? 1 : numSubParts;
        int longest = 0;

        for (Variant record : variants(children(node))) {
            String label = record.label();
            int recordLength = length(record.nodes());
            int totalLength = recordLength * repeats;
            longest = Math.max(longest, totalLength);

            String branch = join(ctx.branch(), label);
            String sheetName = join(ctx.parentName() + " " + collectionId, label);

            if (ctx.sheets() != null) {
                List<FieldRow> recordRows = new ArrayList<>();
                for (int k = 1; k <= repeats; k++) {
                    int recordStart = position + (k - 1) * recordLength;
                    // Only record 1 adds sheets for collections nested inside it.
                    Context recordCtx = new Context(ctx.section(), branch,
                            k == 1 ? ctx.sheets() : null, sheetName,
                            hasSwitch ? ctx.suffix() : ctx.suffix() + " [" + k + "]");
                    layout(record.nodes(), recordRows, recordStart, recordCtx);
                }

                ctx.sheets().add(new SheetLayout(
                        sheetName,
                        ctx.section() + " COLLECTION",
                        branch,
                        "Collection ID=" + collectionId
                                + (label.isEmpty() ? "" : "; " + label)
                                + "; NumSubParts=" + numSubParts
                                + (hasSwitch ? "; sub-case length=" : "; record length=")
                                + recordLength
                                + "; from " + position
                                + " to " + (position + totalLength - 1),
                        List.copyOf(recordRows)
                ));
            }

            rows.add(new FieldRow(
                    position,
                    position + totalLength - 1,
                    totalLength,
                    "Collection",
                    collectionId + (label.isEmpty() ? "" : " (" + label + ")") + ctx.suffix(),
                    "",
                    "COLLECTION",
                    branch,
                    "",
                    repeats,
                    recordLength
            ));
        }

        return position + longest;
    }

    /** True when the nodes hold a SELECT (directly or inside a wrapper, not inside a nested collection). */
    private boolean containsSelect(List<Element> nodes) {
        for (Element node : nodes) {
            String tag = node.getTagName();
            if ("SELECT".equals(tag)) return true;
            if (!"FIELD".equals(tag) && !"COLLECTION".equals(tag)
                    && containsSelect(children(node))) return true;
        }
        return false;
    }

    private static boolean hasFields(List<FieldRow> rows) {
        return rows.stream().anyMatch(row -> "FIELD".equals(row.nodeType()));
    }

    private int indexOfSelect(List<Element> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            if ("SELECT".equals(nodes.get(i).getTagName())) return i;
        }
        return -1;
    }

    private boolean isCase(Element element) {
        String tag = element.getTagName();
        return "CASE".equals(tag) || "CASEELSE".equals(tag);
    }

    /** "CASEID=2", "CASEID=01 " or "CASEELSE". */
    private String branchName(Element branch) {
        return "CASEELSE".equals(branch.getTagName())
                ? "CASEELSE"
                : "CASE" + caseCondition(branch);
    }

    private String switchName(Element select) {
        String name = select.getAttribute("Switch");
        return name.isEmpty() ? select.getAttribute("switch") : name;
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + " " + b;
    }

    private int formatLength(String format) {
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("FIELD Format is missing.");
        }

        if (format.equals("X") || format.equals("9")) return 1;

        if (format.matches("X+") || format.matches("9+")) return format.length();

        if (format.matches("(?i)yyyyMMdd")
                || format.matches("(?i)ddMMyyyy")) {
            return 8;
        }

        Matcher matcher = fieldPattern.matcher(format);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Unsupported field format: " + format);
        }

        int base = matcher.group(2) == null
                ? 1 : Integer.parseInt(matcher.group(2));
        int decimals = matcher.group(3) == null
                ? 0 : Integer.parseInt(matcher.group(3));

        return base + decimals;
    }

    private String dataType(String format) {
        if (format.startsWith("9")) return "Numeric";
        if (format.startsWith("X")) return "Alphanumeric";
        return "Date";
    }

    private int positiveInt(String value, int fallback, String label) {
        if (value == null || value.isBlank()) return fallback;

        try {
            int result = Integer.parseInt(value);
            if (result < 1) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    label + " must be a positive integer.");
        }
    }

    private String caseCondition(Element element) {
        List<String> values = new ArrayList<>();

        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            if (attr.getNodeName().startsWith("ID")) {
                values.add(attr.getNodeName() + "="
                        + attr.getNodeValue());
            }
        }

        return String.join(", ", values);
    }

    private Map<String, String> attributes(Element element) {
        Map<String, String> result = new LinkedHashMap<>();
        NamedNodeMap attrs = element.getAttributes();

        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            result.put(attr.getNodeName(), attr.getNodeValue());
        }

        return Collections.unmodifiableMap(result);
    }

    private Element directChild(Element parent, String tag) {
        for (Element child : children(parent)) {
            if (tag.equals(child.getTagName())) return child;
        }
        return null;
    }

    private List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();

        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                result.add(element);
            }
        }

        return result;
    }

/*
    private List<FieldRow> readSection(Element group, String sectionName) {
        NodeList sections = group.getElementsByTagName(sectionName);
        if (sections.getLength() == 0) return List.of();
        Element section = (Element) sections.item(0);
        NodeList fields = section.getElementsByTagName("FIELD");
        List<FieldRow> result = new ArrayList<>();
        int offset = 1;
        for (int i = 0; i < fields.getLength(); i++) {
            Element field = (Element) fields.item(i);
            String id = field.getAttribute("ID");
            String format = field.getAttribute("Format");
            int length = formatLength(format);
            String dataType = format.startsWith("9") ? "Numeric" :
                (format.startsWith("X") ? "Alphanumeric" : "Date");
            result.add(new FieldRow(id, format, length, dataType, offset, offset + length - 1));
            offset += length;
        }
        return result;
    }

    static int formatLength(String format) {
        // A bare X represents one alphanumeric character.
        if ("X".equals(format)) {
            return 1;
        }

        var matcher = java.util.regex.Pattern
                .compile("^[9X]\\((\\d+)\\)(?:V(\\d+))?S?$")
                .matcher(format);

        if (matcher.matches()) {
            int base = Integer.parseInt(matcher.group(1));
            int decimals = matcher.group(2) == null
                    ? 0
                    : Integer.parseInt(matcher.group(2));

            return base + decimals;
        }

        if (format.matches("(?i)yyyyMMdd")
                || format.matches("(?i)ddMMyyyy")) {
            return 8;
        }

        throw new IllegalArgumentException(
                "Unsupported field format: " + format
        );
    }

 */
}
