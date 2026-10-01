"""Read names attached to controls in the platform accessibility tree."""
import xml.etree.ElementTree as ET


def named_toggles(xml):
    # Compose can expose a merging control's name on a synthetic child node.
    # Limit the search to that control so a nearby page heading cannot name it.
    return {
        name
        for control in ET.fromstring(xml).iter('node')
        if control.get('checkable') == 'true'
        for child in control.iter('node')
        for name in (child.get('text'), child.get('content-desc'))
        if name
    }
