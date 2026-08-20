#!/usr/bin/env python3
from pathlib import Path
import sys

original = Path(__file__).with_name('aniflow_rc13_recovery_patch.py')
script = original.read_text()
old = '''tracks_old = 'override fun onTracksChanged(tracks: Tracks) { tracksVersion++ }'
if tracks_old in s:
    s = s.replace(tracks_old, ''' + "'''" + '''override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                val measured = selectedVideoStats(tracks)
                actualVideoBitrate = measured?.bitrate ?: 0
                actualVideoCodec = measured?.codec
            }''' + "'''" + ''', 1)
req('actualVideoBitrate = measured?.bitrate' in s, 'track telemetry listener missing')
'''
new = '''tracks_signature = 'override fun onTracksChanged(tracks: Tracks)'
if 'actualVideoBitrate = measured?.bitrate' not in s:
    start = s.find(tracks_signature)
    req(start >= 0, 'onTracksChanged listener missing')
    opening = s.find('{', start + len(tracks_signature))
    req(opening >= 0, 'onTracksChanged opening brace missing')

    def _matching_method_brace(text: str, opening_index: int) -> int:
        depth = 0
        state = 'code'
        quote = ''
        i = opening_index
        while i < len(text):
            c = text[i]
            n = text[i + 1] if i + 1 < len(text) else ''
            if state == 'code':
                if c == '/' and n == '/':
                    state = 'line'; i += 2; continue
                if c == '/' and n == '*':
                    state = 'block'; i += 2; continue
                if c in ('"', "'"):
                    quote = c; state = 'string'; i += 1; continue
                if c == '{':
                    depth += 1
                elif c == '}':
                    depth -= 1
                    if depth == 0:
                        return i
                i += 1
            elif state == 'line':
                if c == '\\n': state = 'code'
                i += 1
            elif state == 'block':
                if c == '*' and n == '/':
                    state = 'code'; i += 2
                else:
                    i += 1
            else:
                if c == '\\\\':
                    i += 2; continue
                if c == quote:
                    state = 'code'
                i += 1
        raise SystemExit('RC13_RECOVERY_FAIL: unmatched onTracksChanged braces')

    closing = _matching_method_brace(s, opening)
    body = s[opening + 1:closing]
    # Preserve every RC10 callback statement and append only our measurement.
    indent = '                '
    telemetry = (
        '\\n' + indent + 'val measured = selectedVideoStats(tracks)'
        '\\n' + indent + 'actualVideoBitrate = measured?.bitrate ?: 0'
        '\\n' + indent + 'actualVideoCodec = measured?.codec'
        '\\n            '
    )
    s = s[:closing] + telemetry + s[closing:]
req('actualVideoBitrate = measured?.bitrate' in s, 'track telemetry listener missing')
'''
if old not in script:
    raise SystemExit('RC13_V2_WRAPPER_FAIL: old telemetry patch block not found')
script = script.replace(old, new, 1)
# Execute the original transform with only the telemetry injector changed.
sys.argv = [str(original), *sys.argv[1:]]
namespace = {'__name__': '__main__', '__file__': str(original)}
exec(compile(script, str(original), 'exec'), namespace, namespace)
