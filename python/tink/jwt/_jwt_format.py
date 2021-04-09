# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Functions that help to serialize and deserialize from/to the JWT format."""

import base64
import json

from typing import Any, Text, Tuple

from tink.jwt import _jwt_error


_VALID_ALGORITHMS = frozenset({
    'HS256', 'HS384', 'HS512', 'ES256', 'ES384', 'ES512', 'RS256', 'RS384',
    'RS384', 'RS512', 'PS256', 'PS384', 'PS512'
})


def _base64_encode(data: bytes) -> bytes:
  return base64.urlsafe_b64encode(data).rstrip(b'=')


def _is_valid_urlsafe_base64_char(c: int) -> bool:
  if c >= ord('a') and c <= ord('z'):
    return True
  if c >= ord('A') and c <= ord('Z'):
    return True
  if c >= ord('0') and c <= ord('9'):
    return True
  if c == ord('-') or c == ord('_'):
    return True
  return False


def _base64_decode(encoded_data: bytes) -> bytes:
  # base64.urlsafe_b64decode ignores all non-base64 chars. We don't want that.
  for c in encoded_data:
    if not _is_valid_urlsafe_base64_char(c):
      raise _jwt_error.JwtInvalidError('invalid token')
  # base64.urlsafe_b64decode requires padding, but does not mind too much
  # padding. So we simply add the maximum ammount of padding needed.
  padded_encoded_data = encoded_data + b'==='
  return base64.urlsafe_b64decode(padded_encoded_data)


def json_dumps(json_data: Any) -> Text:
  return json.dumps(json_data, separators=(',', ':'))


def json_loads(json_text: Text) -> Any:
  try:
    return json.loads(json_text)
  except json.decoder.JSONDecodeError:
    raise _jwt_error.JwtInvalidError('Failed to parse JSON string')


def _validate_algorithm(algorithm: Text) -> None:
  if algorithm not in _VALID_ALGORITHMS:
    raise _jwt_error.JwtInvalidError('Invalid algorithm %s' % algorithm)


def encode_header(json_header: Text) -> bytes:
  return _base64_encode(json_header.encode('utf8'))


def decode_header(encoded_header: bytes) -> Text:
  return _base64_decode(encoded_header).decode('utf8')


def encode_payload(json_payload: Text) -> bytes:
  """Encodes the payload into compact form."""
  return _base64_encode(json_payload.encode('utf8'))


def decode_payload(encoded_payload: bytes) -> Text:
  """Decodes the payload from compact form."""
  return _base64_decode(encoded_payload).decode('utf8')


def encode_signature(signature: bytes) -> bytes:
  """Encodes the signature."""
  return _base64_encode(signature)


def decode_signature(encoded_signature: bytes) -> bytes:
  """Decodes the signature."""
  return _base64_decode(encoded_signature)


def create_header(algorithm: Text) -> bytes:
  _validate_algorithm(algorithm)
  return encode_header(json_dumps({'alg': algorithm}))


def split_signed_compact(
    signed_compact: Text) -> Tuple[bytes, Text, Text, bytes]:
  """Splits a signed compact into its parts.

  Args:
    signed_compact: A signed compact JWT.
  Returns:
    A (unsigned_compact, json_header, json_payload, signature_or_mac) tuple.
  Raises:
    _jwt_error.JwtInvalidError if it fails.
  """
  try:
    encoded = signed_compact.encode('utf8')
  except UnicodeEncodeError:
    raise _jwt_error.JwtInvalidError('invalid token')
  try:
    unsigned_compact, encoded_signature = encoded.rsplit(b'.', 1)
  except ValueError:
    raise _jwt_error.JwtInvalidError('invalid token')
  signature_or_mac = decode_signature(encoded_signature)
  try:
    encoded_header, encoded_payload = unsigned_compact.split(b'.')
  except ValueError:
    raise _jwt_error.JwtInvalidError('invalid token')

  json_header = decode_header(encoded_header)
  json_payload = decode_payload(encoded_payload)
  return (unsigned_compact, json_header, json_payload, signature_or_mac)


def validate_header(json_header: Text, algorithm: Text) -> None:
  """Parses the header and validates its values."""
  _validate_algorithm(algorithm)
  decoded_header = json_loads(json_header)
  hdr_algorithm = decoded_header.get('alg', '')
  if hdr_algorithm.upper() != algorithm:
    raise _jwt_error.JwtInvalidError('Invalid algorithm; expected %s, got %s' %
                                     (algorithm, hdr_algorithm))
  header_type = decoded_header.get('typ', None)
  if 'typ' in decoded_header:
    if decoded_header['typ'].upper() != 'JWT':
      raise _jwt_error.JwtInvalidError(
          'Invalid header type; expected JWT, got %s' % decoded_header['typ'])


def create_unsigned_compact(algorithm: Text, json_payload: Text) -> bytes:
  return create_header(algorithm) + b'.' + encode_payload(json_payload)


def create_signed_compact(unsigned_compact: bytes, signature: bytes) -> Text:
  return (unsigned_compact + b'.' + encode_signature(signature)).decode('utf8')
