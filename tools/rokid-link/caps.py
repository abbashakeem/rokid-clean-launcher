"""
Rokid "Caps" binary serialisation — the framing used on the glasses' BLE link.

Wire format (confirmed against yodaos-project/node-caps, the pyrokid_cxr_clientm port, and the
sample frame in its README):

    uint32  total size, big-endian
    uint8   version (5)
    uleb128 member count
    uint8[] one type code per member, in order
    ...     the values, in order, no padding

Type codes and value encodings:
    'i' int32   SLEB128          'u' uint32  ULEB128
    'l' int64   SLEB128          'k' uint64  ULEB128
    'f' float   4 bytes little-endian
    'd' double  8 bytes little-endian
    'S' string  ULEB128 byte length + UTF-8
    'B' binary  ULEB128 byte length + raw bytes
    'O' object  a whole nested Caps frame
    'V' void    no payload
"""
from __future__ import annotations

import struct
from typing import Any

VERSION = 5

INT32, UINT32, INT64, UINT64 = ord('i'), ord('u'), ord('l'), ord('k')
FLOAT, DOUBLE, STRING, BINARY = ord('f'), ord('d'), ord('S'), ord('B')
OBJECT, VOID = ord('O'), ord('V')


def _uleb(n: int) -> bytes:
    if n < 0:
        raise ValueError("uleb128 is unsigned")
    out = bytearray()
    while True:
        byte = n & 0x7F
        n >>= 7
        out.append(byte | (0x80 if n else 0))
        if not n:
            return bytes(out)


def _sleb(n: int) -> bytes:
    out = bytearray()
    while True:
        byte = n & 0x7F
        n >>= 7
        done = (n == 0 and not byte & 0x40) or (n == -1 and byte & 0x40)
        out.append(byte | (0 if done else 0x80))
        if done:
            return bytes(out)


def _read_uleb(buf: bytes, i: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        byte = buf[i]; i += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, i
        shift += 7


def _read_sleb(buf: bytes, i: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        byte = buf[i]; i += 1
        result |= (byte & 0x7F) << shift
        shift += 7
        if not byte & 0x80:
            if byte & 0x40:
                result -= 1 << shift
            return result, i


class Caps:
    """An ordered list of typed values, the unit the glasses exchange over BLE."""

    def __init__(self) -> None:
        self.members: list[tuple[int, Any]] = []

    # ---- writing -------------------------------------------------------
    def write_int32(self, v: int) -> "Caps": self.members.append((INT32, v)); return self
    def write_uint32(self, v: int) -> "Caps": self.members.append((UINT32, v)); return self
    def write_int64(self, v: int) -> "Caps": self.members.append((INT64, v)); return self
    def write_uint64(self, v: int) -> "Caps": self.members.append((UINT64, v)); return self
    def write_float(self, v: float) -> "Caps": self.members.append((FLOAT, v)); return self
    def write_double(self, v: float) -> "Caps": self.members.append((DOUBLE, v)); return self
    def write_string(self, v: str) -> "Caps": self.members.append((STRING, v)); return self
    def write_binary(self, v: bytes) -> "Caps": self.members.append((BINARY, v)); return self
    def write_object(self, v: "Caps") -> "Caps": self.members.append((OBJECT, v)); return self
    def write_void(self) -> "Caps": self.members.append((VOID, None)); return self

    def write(self, v: Any) -> "Caps":
        """Convenience: pick a type from the Python value."""
        if isinstance(v, Caps): return self.write_object(v)
        if isinstance(v, str): return self.write_string(v)
        if isinstance(v, (bytes, bytearray)): return self.write_binary(bytes(v))
        if isinstance(v, bool): return self.write_int32(int(v))
        if isinstance(v, int): return self.write_int64(v) if abs(v) > 0x7FFFFFFF else self.write_int32(v)
        if isinstance(v, float): return self.write_double(v)
        if v is None: return self.write_void()
        raise TypeError(f"unsupported value {type(v)}")

    # ---- reading -------------------------------------------------------
    def at(self, i: int) -> Any:
        return self.members[i][1]

    def types(self) -> str:
        return "".join(chr(t) for t, _ in self.members)

    def serialize(self) -> bytes:
        body = bytearray()
        body += _uleb(len(self.members))
        for t, _ in self.members:
            body.append(t)
        for t, v in self.members:
            if t in (INT32, INT64):      body += _sleb(v)
            elif t in (UINT32, UINT64):  body += _uleb(v)
            elif t == FLOAT:             body += struct.pack('<f', v)
            elif t == DOUBLE:            body += struct.pack('<d', v)
            elif t == STRING:
                enc = v.encode('utf-8'); body += _uleb(len(enc)) + enc
            elif t == BINARY:            body += _uleb(len(v)) + v
            elif t == OBJECT:
                nested = v.serialize(); body += nested
            elif t == VOID:              pass
            else: raise ValueError(f"unknown type {t}")
        total = 5 + len(body)
        return struct.pack('>I', total) + bytes([VERSION]) + bytes(body)

    @classmethod
    def parse(cls, buf: bytes, offset: int = 0) -> tuple["Caps", int]:
        total = struct.unpack_from('>I', buf, offset)[0]
        version = buf[offset + 4]
        if version != VERSION:
            raise ValueError(f"unexpected Caps version {version}")
        i = offset + 5
        count, i = _read_uleb(buf, i)
        type_codes = list(buf[i:i + count]); i += count
        caps = cls()
        for t in type_codes:
            if t in (INT32, INT64):      v, i = _read_sleb(buf, i)
            elif t in (UINT32, UINT64):  v, i = _read_uleb(buf, i)
            elif t == FLOAT:             v = struct.unpack_from('<f', buf, i)[0]; i += 4
            elif t == DOUBLE:            v = struct.unpack_from('<d', buf, i)[0]; i += 8
            elif t == STRING:
                n, i = _read_uleb(buf, i); v = buf[i:i + n].decode('utf-8', 'replace'); i += n
            elif t == BINARY:
                n, i = _read_uleb(buf, i); v = bytes(buf[i:i + n]); i += n
            elif t == OBJECT:
                v, i = cls.parse(buf, i)
            elif t == VOID:              v = None
            else: raise ValueError(f"unknown type code {t!r}")
            caps.members.append((t, v))
        return caps, offset + total

    @classmethod
    def from_bytes(cls, buf: bytes) -> "Caps":
        return cls.parse(buf, 0)[0]

    def __repr__(self) -> str:
        return f"Caps({self.types()!r}, {[v for _, v in self.members]!r})"


if __name__ == "__main__":
    # 1. round trip every type
    c = Caps().write_string("hello").write_uint32(0x1004).write_int32(-7) \
              .write_uint64(1765983621057).write_double(1.5).write_binary(b"\x00\xff")
    wire = c.serialize()
    back = Caps.from_bytes(wire)
    assert back.types() == "SuikdB", back.types()
    assert [v for _, v in back.members] == ["hello", 0x1004, -7, 1765983621057, 1.5, b"\x00\xff"]
    assert struct.unpack('>I', wire[:4])[0] == len(wire)
    print("round trip ok:", back)

    # 2. nesting
    outer = Caps().write_string("outer").write_object(Caps().write_int32(42))
    assert Caps.from_bytes(outer.serialize()).at(1).at(0) == 42
    print("nested ok")

    # 3. the header shape of the published sample frame: size, version 5, 5 members, "SSSuu",
    #    then a 36-byte string (a UUID). Build the same shape and compare the prefix.
    sample_prefix = b'\x00\x00\x00\x99\x05\x05SSSuu$'
    mine = Caps().write_string("b5a2f4c1-0000-0000-0000-00000000abcd") \
                 .write_string("AA:BB:CC:DD:EE:FF").write_string("acct").write_uint32(1).write_uint32(0)
    got = mine.serialize()
    assert got[4:11] == sample_prefix[4:11], (got[4:11], sample_prefix[4:11])
    assert got[11] == 0x24, got[11]          # ULEB128 length of a 36-char UUID
    print("matches published sample frame layout:", got[:12].hex(' '))
