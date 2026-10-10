"""Render the same geometric Kartal monogram for desktop and Android."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
assets = ROOT / 'assets'
assets.mkdir(exist_ok=True)
paths = [('M 32 27 L 43 27 L 43 48 L 65 27 L 80 27 L 53 53 L 80 80 L 65 80 L 43 59 L 43 80 L 32 80 Z', '#6CFFC0')]
svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" rx="25" fill="#061C18"/><rect x="8" y="8" width="92" height="92" rx="20" fill="none" stroke="#1EDB93" stroke-width="2"/>'
for path, color in paths:
    svg += f'<path d="{path}" fill="{color}"/>'
svg += '</svg>'
(assets / 'kartal.svg').write_text(svg)
image = Image.new('RGBA', (864, 864))
d = ImageDraw.Draw(image)
d.rounded_rectangle((0, 0, 863, 863), radius=200, fill='#061C18')
d.rounded_rectangle((64, 64, 800, 800), radius=160, outline='#1EDB93', width=16)
points = [(32,27),(43,27),(43,48),(65,27),(80,27),(53,53),(80,80),(65,80),(43,59),(43,80),(32,80)]
d.polygon([(x*8,y*8) for x,y in points], fill='#6CFFC0')
image.resize((256,256), Image.Resampling.LANCZOS).save(assets/'kartal.png')
image.save(assets/'kartal.ico', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
res = ROOT/'android/app/src/main/res'
vector = '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108"><path android:fillColor="#061C18" android:pathData="M0,0H108V108H0Z"/>'
for path,color in paths:
    vector += f'<path android:fillColor="{color}" android:pathData="{path}"/>'
vector += '</vector>'
(res/'drawable/ic_launcher.xml').write_text(vector)
adaptive = res/'mipmap-anydpi-v26'
adaptive.mkdir(exist_ok=True)
(adaptive/'ic_launcher.xml').write_text('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/icon_background"/><foreground android:drawable="@drawable/ic_launcher"/></adaptive-icon>')
(res/'values/icon_colors.xml').write_text('<resources><color name="icon_background">#061C18</color></resources>')
print('Kartal icons generated')
