#!/usr/bin/env python3
"""Check that a simulator screenshot contains the dark MOV UI and an accent."""
import pathlib
import struct
import sys
import zlib


def screen_coverage(path: pathlib.Path):
    data = path.read_bytes()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('not a PNG')
    offset = 8
    compressed = bytearray()
    width = height = channels = None
    while offset < len(data):
        length = struct.unpack_from('>I', data, offset)[0]
        kind = data[offset + 4:offset + 8]
        chunk = data[offset + 8:offset + 8 + length]
        offset += length + 12
        if kind == b'IHDR':
            width, height, depth, color, _, _, interlace = struct.unpack('>IIBBBBB', chunk)
            if depth != 8 or color not in (2, 6) or interlace:
                raise ValueError(f'unsupported screenshot PNG: depth={depth}, color={color}, interlace={interlace}')
            channels = 4 if color == 6 else 3
        elif kind == b'IDAT':
            compressed.extend(chunk)
        elif kind == b'IEND':
            break
    if not compressed or not width or not height:
        raise ValueError('missing PNG image data')
    raw = zlib.decompress(compressed)
    row_size = width * channels
    previous = bytearray(row_size)
    position = 0
    dark = amber = coral = samples = 0
    x_step = max(1, width // 40)
    y_step = max(1, height // 50)
    for y in range(height):
        filter_type = raw[position]
        position += 1
        row = bytearray(raw[position:position + row_size])
        position += row_size
        if len(row) != row_size:
            raise ValueError('truncated PNG row')
        for i in range(row_size):
            left = row[i - channels] if i >= channels else 0
            above = previous[i]
            upper_left = previous[i - channels] if i >= channels else 0
            if filter_type == 1:
                row[i] = (row[i] + left) & 255
            elif filter_type == 2:
                row[i] = (row[i] + above) & 255
            elif filter_type == 3:
                row[i] = (row[i] + ((left + above) // 2)) & 255
            elif filter_type == 4:
                prediction = left + above - upper_left
                distances = (abs(prediction - left), abs(prediction - above), abs(prediction - upper_left))
                predictor = (left, above, upper_left)[distances.index(min(distances))]
                row[i] = (row[i] + predictor) & 255
            elif filter_type != 0:
                raise ValueError(f'unsupported PNG filter {filter_type}')
        if height // 6 <= y < height * 5 // 6 and y % y_step == 0:
            for x in range(width // 8, width * 7 // 8, x_step):
                i = x * channels
                red, green, blue = row[i:i + 3]
                samples += 1
                if (red * 3 + green * 6 + blue) / 10 < 90:
                    dark += 1
                if red > 180 and 110 < green < 225 and blue < 120:
                    amber += 1
                if red > 180 and 75 < green <= 130 and 55 < blue < 155:
                    coral += 1
        previous = row
    return dark / samples, amber, coral


if __name__ == '__main__':
    fraction, amber, coral = screen_coverage(pathlib.Path(sys.argv[1]))
    print(f'dark={fraction:.2f} amber_samples={amber} coral_samples={coral}')
    if '--boot' in sys.argv[2:]:
        # The simulator's black Apple-logo boot splash is ~96% dark. Wait for
        # SpringBoard before installing and launching the app.
        sys.exit(0 if fraction < 0.90 else 1)
    sys.exit(0 if fraction >= 0.35 and (amber >= 3 or coral >= 3) else 1)
