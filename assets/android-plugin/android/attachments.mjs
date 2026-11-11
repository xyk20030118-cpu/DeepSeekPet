/**
 * Android 版 `ctx.attachments` —— 上游 `@deepseek-ai/dsh-attachment` 的服务实现。
 *
 * <h3>为什么不用上游的 `dsh-attachment-local`</h3>
 * 它在"归一化"这一步依赖 `sharp`（libvips 原生模块），Android 上没有可用的 libvips 构建。
 * 本实现保留上游**完整的契约**（内容寻址、校验、批量准入、verbatim 文件存储），
 * 只把"缩图/转码"这一段换成如实的能力声明：
 *
 * | 能力 | 本次构建 |
 * |---|---|
 * | 图片头解析（PNG/JPEG/GIF/WebP 的尺寸/透明） | ✅ 纯 JS 实现 |
 * | 字节上限、张数上限、像素上限、媒体类型一致性 | ✅ 与上游同一套判定 |
 * | 内容寻址落盘（`objects/<xx>/<sha256>`） | ✅ |
 * | verbatim 文件存储（`file-objects` + `files/<xx>/<sha>/<name>`） | ✅ |
 * | **缩图 / 转码**（上游用 sharp） | ❌ 没有后端 ⇒ **超预算时明确拒绝**，不假装成功 |
 *
 * 最后一行是这条实现最重要的诚实边界：小于归一化预算（4M 像素 / 4MB）的图**原样通过**，
 * 超过的图会在准入阶段被拒并给出可执行的原因，而不是悄悄存一张过大的图让模型请求失败。
 *
 * @module dsh-android-providers/attachments
 */
import { mkdir, link, readFile, rename, rm, unlink, writeFile } from 'node:fs/promises'
import { createHash, randomUUID } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { AttachmentError, AttachmentId, AttachmentStore, ImageVariantId, requestImageDimensions } from '@deepseek-ai/dsh-attachment'
import { resolveDshHome } from '@deepseek-ai/dsh-home-paths'

/** 一份上传里允许的图片张数。 */
const DEFAULT_MAX_IMAGES_PER_MESSAGE = 20
/** 单张图片编码字节上限（源图）。 */
const DEFAULT_MAX_IMAGE_BYTES = 20 * 1024 * 1024
/** 一条消息里图片总字节上限。 */
const DEFAULT_MAX_MESSAGE_IMAGE_BYTES = 200 * 1024 * 1024
/** 源图总像素上限。 */
const DEFAULT_MAX_IMAGE_PIXELS = 64e6
/** 源图单边上限。 */
const DEFAULT_MAX_IMAGE_DIMENSION = 8192
/** 归一化后的总像素预算（上游默认 2048×2048）。 */
const DEFAULT_NORMALIZED_MAX_PIXELS = 2048 * 2048
/** 归一化后的单边上限。 */
const DEFAULT_NORMALIZED_MAX_DIMENSION = 8192
/** 归一化后的字节预算。 */
const DEFAULT_NORMALIZED_MAX_BYTES = 4 * 1024 * 1024

/** 接受的图片类型。 */
const MEDIA_TYPES = Object.freeze(['image/png', 'image/jpeg', 'image/webp', 'image/gif'])

/** 引用里的摘要格式。 */
const ID_PATTERN = /^sha256:([a-f0-9]{64})$/

/** 大文件读写块大小。 */
const CHUNK_BYTES = 64 * 1024

/**
 * 算 sha256（十六进制）。
 * @param {Uint8Array|Buffer} data 字节。
 * @returns {string} 摘要。
 */
function digest(data) {
  return createHash('sha256').update(data).digest('hex')
}

/**
 * 从引用里取出摘要，形状不对就按上游的码报错。
 * @param {{attachmentId?: unknown}} ref 附件引用。
 * @param {string} code 失败码。
 * @returns {string} 64 位十六进制摘要。
 */
function digestOf(ref, code) {
  const match = ID_PATTERN.exec(String(ref?.attachmentId))
  if (match?.[1] === undefined) throw new AttachmentError('Attachment reference is invalid.', code)
  return match[1]
}

/**
 * 解析图片头，拿到媒体类型与尺寸。只读前若干字节，不解码像素。
 * @param {Uint8Array} bytes 图片字节。
 * @returns {{mediaType: string, width: number, height: number, hasAlpha: boolean}|undefined}
 *   认不出来的格式返回 undefined（调用方按 INVALID_IMAGE 处理）。
 */
function probeImage(bytes) {
  const b = bytes
  if (b.length >= 33 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47
    && b[4] === 0x0d && b[5] === 0x0a && b[6] === 0x1a && b[7] === 0x0a) {
    // PNG：IHDR 紧跟签名（长度 4 + 类型 4）
    if (b[12] !== 0x49 || b[13] !== 0x48 || b[14] !== 0x44 || b[15] !== 0x52) return undefined
    const width = readU32BE(b, 16)
    const height = readU32BE(b, 20)
    const colorType = b[25]
    if (width === 0 || height === 0) return undefined
    return { mediaType: 'image/png', width, height, hasAlpha: colorType === 4 || colorType === 6 }
  }
  if (b.length >= 10 && b[0] === 0xff && b[1] === 0xd8) {
    // JPEG：扫段找 SOFn
    let offset = 2
    while (offset + 9 < b.length) {
      if (b[offset] !== 0xff) {
        offset += 1
        continue
      }
      const marker = b[offset + 1]
      if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
        offset += 2
        continue
      }
      const length = readU16BE(b, offset + 2)
      if (length < 2) return undefined
      const isSof = marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc
      if (isSof) {
        const height = readU16BE(b, offset + 5)
        const width = readU16BE(b, offset + 7)
        if (width === 0 || height === 0) return undefined
        return { mediaType: 'image/jpeg', width, height, hasAlpha: false }
      }
      offset += 2 + length
    }
    return undefined
  }
  if (b.length >= 10 && b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x38
    && (b[4] === 0x37 || b[4] === 0x39) && b[5] === 0x61) {
    const width = readU16LE(b, 6)
    const height = readU16LE(b, 8)
    if (width === 0 || height === 0) return undefined
    return { mediaType: 'image/gif', width, height, hasAlpha: true }
  }
  if (b.length >= 30 && b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x46
    && b[8] === 0x57 && b[9] === 0x45 && b[10] === 0x42 && b[11] === 0x50) {
    const chunk = String.fromCharCode(b[12], b[13], b[14], b[15])
    if (chunk === 'VP8X') {
      const width = 1 + (b[24] | (b[25] << 8) | (b[26] << 16))
      const height = 1 + (b[27] | (b[28] << 8) | (b[29] << 16))
      return { mediaType: 'image/webp', width, height, hasAlpha: (b[20] & 0x10) !== 0 }
    }
    if (chunk === 'VP8 ') {
      const width = readU16LE(b, 26) & 0x3fff
      const height = readU16LE(b, 28) & 0x3fff
      if (width === 0 || height === 0) return undefined
      return { mediaType: 'image/webp', width, height, hasAlpha: false }
    }
    if (chunk === 'VP8L') {
      const bits = readU32LE(b, 21)
      const width = (bits & 0x3fff) + 1
      const height = ((bits >> 14) & 0x3fff) + 1
      return { mediaType: 'image/webp', width, height, hasAlpha: ((bits >> 28) & 1) === 1 }
    }
    return undefined
  }
  return undefined
}

function readU16BE(b, i) { return (b[i] << 8) | b[i + 1] }
function readU16LE(b, i) { return b[i] | (b[i + 1] << 8) }
function readU32BE(b, i) { return ((b[i] << 24) | (b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3]) >>> 0 }
function readU32LE(b, i) { return ((b[i]) | (b[i + 1] << 8) | (b[i + 2] << 16) | (b[i + 3] << 24)) >>> 0 }

/**
 * 把调用方给的名字清洗成一个安全的叶子名（与上游 `fileLeafName` 同义，
 * 目的是不让客户端的完整路径泄进引用与会话日志）。
 * @param {string|undefined} value 声明名。
 * @returns {string} 安全叶子名。
 */
function fileLeafName(value) {
  if (value === undefined) return 'file'
  let clean = value.slice(Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\')) + 1)
    .replace(/[\u0000-\u001f\u007f]/g, '')
    .replace(/[<>:"|?*]/g, '_')
    .trim()
    .replace(/[. ]+$/u, '')
  if (/^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])$/iu.test(clean.split('.')[0])) clean = `_${clean}`
  if (clean.length > 255) clean = clean.slice(0, 255)
  return clean === '' || clean === '.' || clean === '..' ? 'file' : clean
}

/** Android 附件存储。 */
export default class AndroidAttachmentStore extends AttachmentStore {
  /**
   * @param {object} ctx cordis 上下文。
   * @param {object|undefined} config 条目配置（本实现无必填项）。
   */
  constructor(ctx, config = {}) {
    super(ctx)
    this.root = resolve(join(resolveDshHome(config.dshHome), 'attachments', 'v1'))
    this.imageLimits = Object.freeze({
      maxImageBytes: config.maxImageBytes ?? DEFAULT_MAX_IMAGE_BYTES,
      maxImagesPerMessage: config.maxImagesPerMessage ?? DEFAULT_MAX_IMAGES_PER_MESSAGE,
      maxMessageImageBytes: config.maxMessageImageBytes ?? DEFAULT_MAX_MESSAGE_IMAGE_BYTES,
      maxImagePixels: config.maxImagePixels ?? DEFAULT_MAX_IMAGE_PIXELS,
      maxImageDimension: config.maxImageDimension ?? DEFAULT_MAX_IMAGE_DIMENSION,
      mediaTypes: MEDIA_TYPES,
    })
    this.normalizationPolicy = Object.freeze({
      maxPixels: config.normalizedImageMaxPixels ?? DEFAULT_NORMALIZED_MAX_PIXELS,
      maxDimension: config.normalizedImageMaxDimension ?? DEFAULT_NORMALIZED_MAX_DIMENSION,
      maxBytes: config.normalizedImageMaxBytes ?? DEFAULT_NORMALIZED_MAX_BYTES,
    })
    this.ctx.logger?.info?.(
      `attachments: 内容寻址存储在 ${this.root}；归一化只做"原样通过"，无缩放后端。`,
    )
  }

  /** 归一化对象的落盘路径。 */
  imageObjectPath(sha256) {
    return join(this.root, 'objects', sha256.slice(0, 2), sha256)
  }

  /** verbatim 文件的规范对象路径（按摘要，与显示名无关）。 */
  fileObjectPath(sha256) {
    return join(this.root, 'file-objects', sha256.slice(0, 2), sha256)
  }

  /** verbatim 文件的可读别名路径（以真实文件名结尾，模型看到的就是它）。 */
  fileAliasPath(sha256, name) {
    return join(this.root, 'files', sha256.slice(0, 2), sha256, name)
  }

  /**
   * 准入校验：不改存储，只判定这批图能不能收。
   * @param {{data: Uint8Array, mediaType: string, name?: string}} input 一张图。
   */
  async validateImage(input) {
    this.#inspect(input)
  }

  /**
   * 把一张已校验的图落盘（内容寻址）。
   * @param {{data: Uint8Array, mediaType: string, name?: string}} input 一张图。
   * @returns {Promise<object>} 持久引用。
   */
  async saveImage(input) {
    const detected = this.#inspect(input)
    const sha256 = digest(input.data)
    await this.#publish(this.imageObjectPath(sha256), input.data, sha256)
    const name = input.name === undefined ? undefined : fileLeafName(input.name)
    return {
      attachmentId: AttachmentId(`sha256:${sha256}`),
      mediaType: detected.mediaType,
      width: detected.width,
      height: detected.height,
      bytes: input.data.byteLength,
      ...(name !== undefined ? { name } : {}),
    }
  }

  /**
   * 读回一张已存的图并校验完整性。
   * @param {object} ref 引用。
   * @param {AbortSignal|undefined} signal 取消。
   */
  async readImage(ref, signal) {
    signal?.throwIfAborted()
    const sha256 = digestOf(ref, 'INVALID_ATTACHMENT_REF')
    let data
    try {
      data = new Uint8Array(await readFile(this.imageObjectPath(sha256), { signal }))
    } catch (error) {
      signal?.throwIfAborted()
      if (error?.code === 'ENOENT') throw new AttachmentError('Attachment object is missing.', 'ATTACHMENT_NOT_FOUND')
      throw new AttachmentError('Unable to read image attachment.', 'ATTACHMENT_READ_FAILED', { cause: error })
    }
    signal?.throwIfAborted()
    if (digest(data) !== sha256) throw new AttachmentError('Stored attachment failed integrity verification.', 'ATTACHMENT_CORRUPT')
    const detected = probeImage(data)
    if (detected === undefined) throw new AttachmentError('Stored image is not decodable.', 'ATTACHMENT_CORRUPT')
    if (detected.mediaType !== ref.mediaType || data.byteLength !== ref.bytes
      || detected.width !== ref.width || detected.height !== ref.height) {
      throw new AttachmentError('Stored attachment metadata does not match its reference.', 'ATTACHMENT_CORRUPT')
    }
    return { ref, data }
  }

  /** 已归一化图片的宿主路径（供"在 App 里打开"一类能力使用）。 */
  imageHostPath(ref) {
    return this.imageObjectPath(digestOf(ref, 'INVALID_ATTACHMENT_REF'))
  }

  /**
   * verbatim 存文件（逐字节，不做任何转换）。
   * @param {{data: Uint8Array, name?: string}} input 文件字节与显示名。
   */
  async saveFile(input) {
    const sha256 = digest(input.data)
    const ref = {
      attachmentId: AttachmentId(`sha256:${sha256}`),
      name: fileLeafName(input.name),
      bytes: input.data.byteLength,
    }
    const objectPath = this.fileObjectPath(sha256)
    await this.#publish(objectPath, input.data, sha256)
    await this.#alias(objectPath, this.fileAliasPath(sha256, ref.name))
    return ref
  }

  /**
   * verbatim 存文件（分块，不把整份收进内存）。
   * @param {{data: AsyncIterable<Uint8Array>, name?: string, signal?: AbortSignal}} input 分块输入。
   */
  async saveFileStream(input) {
    const name = fileLeafName(input.name)
    const hash = createHash('sha256')
    const chunks = []
    let bytes = 0
    for await (const chunk of input.data) {
      input.signal?.throwIfAborted()
      const buf = Buffer.from(chunk)
      hash.update(buf)
      chunks.push(buf)
      bytes += buf.byteLength
    }
    input.signal?.throwIfAborted()
    const sha256 = hash.digest('hex')
    const data = Buffer.concat(chunks)
    const ref = { attachmentId: AttachmentId(`sha256:${sha256}`), name, bytes }
    const objectPath = this.fileObjectPath(sha256)
    await this.#publish(objectPath, data, sha256)
    await this.#alias(objectPath, this.fileAliasPath(sha256, name))
    return ref
  }

  /**
   * 分块读回 verbatim 文件，边读边校验摘要。
   * @param {object} ref 引用。
   * @param {AbortSignal|undefined} signal 取消。
   */
  async *readFileStream(ref, signal) {
    signal?.throwIfAborted()
    const sha256 = digestOf(ref, 'INVALID_ATTACHMENT_REF')
    const path = this.fileObjectPath(sha256)
    const hash = createHash('sha256')
    let bytes = 0
    const stream = createReadStream(path, { highWaterMark: CHUNK_BYTES })
    try {
      for await (const chunk of stream) {
        signal?.throwIfAborted()
        hash.update(chunk)
        bytes += chunk.length
        yield new Uint8Array(chunk)
      }
    } catch (error) {
      if (error?.code === 'ENOENT') throw new AttachmentError('Stored file is missing.', 'ATTACHMENT_NOT_FOUND')
      throw error
    }
    signal?.throwIfAborted()
    if (hash.digest('hex') !== sha256 || bytes !== ref.bytes) {
      throw new AttachmentError('Stored file failed integrity verification.', 'ATTACHMENT_CORRUPT')
    }
  }

  /** verbatim 文件的宿主路径。 */
  fileHostPath(ref) {
    const sha256 = digestOf(ref, 'INVALID_ATTACHMENT_REF')
    return this.fileAliasPath(sha256, fileLeafName(ref.name))
  }

  /**
   * 生成/读取一次模型请求的图片版本。
   *
   * 本构建没有缩放后端：**已经在预算内就原样返回**，超出预算则明确拒绝
   * （而不是返回一张超预算的图让上游请求失败）。
   * @param {object} ref 已归一化图片的引用。
   * @param {{maxPixels: number, maxBytes: number}} policy 这次路由的预算。
   * @param {AbortSignal|undefined} signal 取消。
   */
  async readImageRequest(ref, policy, signal) {
    signal?.throwIfAborted()
    const { data } = await this.readImage(ref, signal)
    const dims = requestImageDimensions(ref.width, ref.height, policy.maxPixels)
    if (dims.width !== ref.width || dims.height !== ref.height || data.byteLength > policy.maxBytes) {
      throw new AttachmentError(
        '这张图超出了本次模型请求的尺寸/字节预算，而本构建没有图像缩放后端（libvips 在 Android 上不可用）。'
        + `请先把图片缩到 ${policy.maxPixels} 像素以内、${policy.maxBytes} 字节以内再上传。`,
        'ATTACHMENT_PROJECTION_UNSUPPORTED',
      )
    }
    const detected = probeImage(data)
    const variantId = ImageVariantId(`sha256:${digest(Buffer.from(JSON.stringify({
      id: String(ref.attachmentId),
      maxPixels: policy.maxPixels,
      maxBytes: policy.maxBytes,
    })))}`)
    return {
      variantId,
      attachment: ref,
      data,
      mediaType: ref.mediaType,
      bytes: data.byteLength,
      width: ref.width,
      height: ref.height,
      depth: 'uchar',
      space: 'srgb',
      hasAlpha: detected?.hasAlpha === true,
    }
  }

  /**
   * 一张图的准入判定（源限制 + 归一化预算）。
   * @param {{data: Uint8Array, mediaType: string}} input 一张图。
   * @returns {{mediaType: string, width: number, height: number, hasAlpha: boolean}}
   * @throws {AttachmentError} 任一限制不满足。
   */
  #inspect(input) {
    const { maxImageBytes, maxImagePixels, maxImageDimension, mediaTypes } = this.imageLimits
    const policy = this.normalizationPolicy
    if (!(input.data instanceof Uint8Array)) throw new AttachmentError('Image payload must be bytes.', 'INVALID_IMAGE')
    if (input.data.byteLength === 0) throw new AttachmentError('Image is empty.', 'INVALID_IMAGE')
    if (input.data.byteLength > maxImageBytes) throw new AttachmentError('Image exceeds the configured byte limit.', 'IMAGE_TOO_LARGE')
    const detected = probeImage(input.data)
    if (detected === undefined) throw new AttachmentError('Unsupported or malformed image data.', 'INVALID_IMAGE')
    if (!mediaTypes.includes(input.mediaType)) throw new AttachmentError(`Image type ${input.mediaType} is not accepted by this deployment.`, 'UNSUPPORTED_IMAGE_TYPE')
    if (detected.mediaType !== input.mediaType) throw new AttachmentError('Declared image type does not match its bytes.', 'IMAGE_TYPE_MISMATCH')
    if (detected.width * detected.height > maxImagePixels) throw new AttachmentError('Image exceeds the configured pixel limit.', 'IMAGE_TOO_MANY_PIXELS')
    if (Math.max(detected.width, detected.height) > maxImageDimension) throw new AttachmentError('Image exceeds the configured dimension limit.', 'IMAGE_DIMENSION_TOO_LARGE')
    const withinNormalization = detected.width * detected.height <= policy.maxPixels
      && Math.max(detected.width, detected.height) <= policy.maxDimension
      && input.data.byteLength <= policy.maxBytes
    if (!withinNormalization) {
      throw new AttachmentError(
        '这张图需要归一化（缩小/转码），而本构建没有图像缩放后端（libvips 在 Android 上不可用）。'
        + `请先把图片缩到 ${policy.maxPixels} 像素以内、单边 ${policy.maxDimension} 以内、${policy.maxBytes} 字节以内再上传。`,
        'ATTACHMENT_WRITE_FAILED',
      )
    }
    return detected
  }

  /**
   * 原子发布一个不可变对象（临时名 → 校验 → rename）。
   * @param {string} path 目标路径。
   * @param {Uint8Array} data 字节。
   * @param {string} sha256 期望摘要。
   */
  async #publish(path, data, sha256) {
    await mkdir(dirname(path), { recursive: true, mode: 0o700 })
    const temporary = `${path}.${randomUUID()}.tmp`
    try {
      await writeFile(temporary, data, { mode: 0o600, flag: 'wx' })
      const written = new Uint8Array(await readFile(temporary))
      if (digest(written) !== sha256) throw new AttachmentError('Attachment bytes failed verification before publication.', 'ATTACHMENT_WRITE_FAILED')
      try {
        await rename(temporary, path)
      } catch (error) {
        if (error?.code !== 'EEXIST') throw error
        await rm(temporary, { force: true })
      }
    } catch (error) {
      try { await unlink(temporary) } catch {}
      if (error instanceof AttachmentError) throw error
      throw new AttachmentError('Unable to publish attachment object.', 'ATTACHMENT_WRITE_FAILED', { cause: error })
    }
  }

  /**
   * 给逐字节对象加一个以真实文件名结尾的别名（硬链接优先，失败退化为复制）。
   * @param {string} objectPath 规范对象路径。
   * @param {string} aliasPath 别名路径。
   */
  async #alias(objectPath, aliasPath) {
    await mkdir(dirname(aliasPath), { recursive: true, mode: 0o700 })
    try {
      await link(objectPath, aliasPath)
    } catch (error) {
      if (error?.code === 'EEXIST') return
      try {
        const data = await readFile(objectPath)
        await writeFile(aliasPath, data, { mode: 0o600, flag: 'wx' })
      } catch (inner) {
        if (inner?.code === 'EEXIST') return
        throw new AttachmentError('Unable to publish attachment alias.', 'ATTACHMENT_WRITE_FAILED', { cause: inner })
      }
    }
  }
}
