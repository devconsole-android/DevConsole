import re
import os

html_path = 'sdk/server-ktor/src/main/resources/devconsole-web/index.html'
css_path = 'sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css'

with open(html_path, 'r') as f:
    html = f.read()

# Replace metrics-strip with metric-strip
html = html.replace('metrics-strip', 'metric-strip')

with open(html_path, 'w') as f:
    f.write(html)
