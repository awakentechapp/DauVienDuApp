import xml.etree.ElementTree as ET
import re
import sys
import os

def parse_transform(transform_str):
    if not transform_str:
        return {}
    res = {}
    # match translate(x, y) or translate(x y) or translate(x)
    m_trans = re.search(r'translate\(\s*([-\d.]+)(?:[\s,]+([-\d.]+))?\s*\)', transform_str)
    if m_trans:
        res['tx'] = float(m_trans.group(1))
        res['ty'] = float(m_trans.group(2)) if m_trans.group(2) is not None else 0.0
    # match scale(sx, sy) or scale(sx sy) or scale(s)
    m_scale = re.search(r'scale\(\s*([-\d.]+)(?:[\s,]+([-\d.]+))?\s*\)', transform_str)
    if m_scale:
        res['sx'] = float(m_scale.group(1))
        res['sy'] = float(m_scale.group(2)) if m_scale.group(2) is not None else res['sx']
    return res

def convert_svg_to_vector_drawable(svg_path, out_xml_path):
    tree = ET.parse(svg_path)
    root = tree.getroot()

    # Strip XML namespace
    for elem in root.iter():
        if '}' in elem.tag:
            elem.tag = elem.tag.split('}', 1)[1]

    viewbox = root.attrib.get('viewBox', '0 0 256 256').split()
    vw = float(viewbox[2])
    vh = float(viewbox[3])

    xml_lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{int(vw)}dp"',
        f'    android:height="{int(vh)}dp"',
        f'    android:viewportWidth="{vw:g}"',
        f'    android:viewportHeight="{vh:g}">'
    ]

    def process_node(node, inherited_fill=None, indent="    "):
        tag = node.tag
        if tag == 'title' or tag == 'desc':
            return

        fill = node.attrib.get('fill', inherited_fill)
        transform_str = node.attrib.get('transform')
        trans_attrs = parse_transform(transform_str) if transform_str else {}

        has_group = bool(trans_attrs)

        if has_group:
            attrs = []
            if 'tx' in trans_attrs and trans_attrs['tx'] != 0:
                attrs.append(f'android:translateX="{trans_attrs["tx"]:g}"')
            if 'ty' in trans_attrs and trans_attrs['ty'] != 0:
                attrs.append(f'android:translateY="{trans_attrs["ty"]:g}"')
            if 'sx' in trans_attrs and trans_attrs['sx'] != 1:
                attrs.append(f'android:scaleX="{trans_attrs["sx"]:g}"')
            if 'sy' in trans_attrs and trans_attrs['sy'] != 1:
                attrs.append(f'android:scaleY="{trans_attrs["sy"]:g}"')
            attr_str = " ".join(attrs)
            if attr_str:
                xml_lines.append(f'{indent}<group {attr_str}>')
            else:
                xml_lines.append(f'{indent}<group>')
            child_indent = indent + "    "
        else:
            child_indent = indent

        if tag == 'rect':
            x = float(node.attrib.get('x', 0))
            y = float(node.attrib.get('y', 0))
            w = float(node.attrib.get('width', 0))
            h = float(node.attrib.get('height', 0))
            # simple rectangle path: M x,y h w v h h -w Z
            path_d = f"M{x:g} {y:g}h{w:g}v{h:g}h{-w:g}Z"
            fill_col = node.attrib.get('fill', fill)
            if fill_col and fill_col != 'none':
                xml_lines.append(f'{child_indent}<path')
                xml_lines.append(f'{child_indent}    android:fillColor="{fill_col}"')
                xml_lines.append(f'{child_indent}    android:pathData="{path_d}" />')
        elif tag == 'path':
            d = node.attrib.get('d', '')
            fill_rule = node.attrib.get('fill-rule', '').lower()
            fill_col = node.attrib.get('fill', fill)
            if fill_col and fill_col != 'none' and d:
                # clean whitespace in path
                d_clean = " ".join(d.split())
                xml_lines.append(f'{child_indent}<path')
                xml_lines.append(f'{child_indent}    android:fillColor="{fill_col}"')
                if fill_rule == 'evenodd':
                    xml_lines.append(f'{child_indent}    android:fillType="evenOdd"')
                xml_lines.append(f'{child_indent}    android:pathData="{d_clean}" />')
        elif tag == 'g':
            for child in node:
                process_node(child, inherited_fill=fill, indent=child_indent)

        if has_group:
            xml_lines.append(f'{indent}</group>')

    for child in root:
        process_node(child, inherited_fill=None)

    xml_lines.append('</vector>')
    os.makedirs(os.path.dirname(out_xml_path), exist_ok=True)
    with open(out_xml_path, 'w', encoding='utf-8') as f:
        f.write("\n".join(xml_lines) + "\n")
    print(f"Generated: {out_xml_path}")

if __name__ == '__main__':
    if len(sys.argv) > 2:
        convert_svg_to_vector_drawable(sys.argv[1], sys.argv[2])
    else:
        print("Usage: python convert_svg_to_vector_drawable.py input.svg output.xml")
