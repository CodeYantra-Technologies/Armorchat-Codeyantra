"""Minimal UI inspection restricted to app navigation labels; never dumps QR contents."""
import subprocess
import sys
import xml.etree.ElementTree as ET

serial = {'A': 'CNU763H001471307631', 'B': 'R9EMA08ZTVJ'}[sys.argv[1]]
subprocess.run(['adb', '-s', serial, 'shell', 'uiautomator', 'dump', '/sdcard/armorchat-diagnostic-ui.xml'], stdout=subprocess.DEVNULL, check=True)
raw = subprocess.check_output(['adb', '-s', serial, 'shell', 'cat', '/sdcard/armorchat-diagnostic-ui.xml'])
labels = {'Chats', 'Contacts', 'Settings', 'Add Contact', 'My Profile', 'My ArmorChat QR',
          'Scan QR', 'Scan QR Code', 'Scan with Camera', 'Camera', 'Back', 'My Phone A',
          'My Phone B', 'Phone A', 'Phone B', 'Choose from Gallery', 'Allow',
          'While using the app', 'Only this time', 'Securing chat…', 'ESTABLISHED',
          'Secure chat', 'Confirm', 'Save Contact', 'Save', 'Done', 'Profile',
          'Show QR', 'My QR', 'Chat', 'Message', 'Verify', 'Cancel', 'Continue',
          'Scan ArmorChat QR', 'Contact already exists'}
for node in ET.fromstring(raw).iter('node'):
    text = node.get('text') or node.get('content-desc')
    if text in labels:
        print(text, node.get('bounds'), 'enabled=' + node.get('enabled', ''))
