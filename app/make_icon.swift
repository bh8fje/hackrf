import AppKit

let destination = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let preview = URL(fileURLWithPath: CommandLine.arguments[2])
try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
let image = NSImage(size: NSSize(width: 1024, height: 1024))
image.lockFocus()
let tile = NSBezierPath(roundedRect: NSRect(x: 64, y: 64, width: 896, height: 896), xRadius: 200, yRadius: 200)
let shadow = NSShadow(); shadow.shadowColor = NSColor.black.withAlphaComponent(0.28)
shadow.shadowBlurRadius = 25; shadow.shadowOffset = NSSize(width: 0, height: -10)
NSGraphicsContext.saveGraphicsState(); shadow.set()
NSColor(calibratedRed: 0.03, green: 0.12, blue: 0.22, alpha: 1).setFill(); tile.fill()
NSGraphicsContext.restoreGraphicsState()
NSGradient(starting: NSColor(calibratedRed: 0.02, green: 0.11, blue: 0.23, alpha: 1),
           ending: NSColor(calibratedRed: 0.03, green: 0.42, blue: 0.49, alpha: 1))!.draw(in: tile, angle: 60)
let mint = NSColor(calibratedRed: 0.25, green: 0.96, blue: 0.80, alpha: 1)
// A quiet orbital track stays recognizable at Dock sizes.
NSGraphicsContext.saveGraphicsState()
let transform = NSAffineTransform(); transform.translateX(by: 512, yBy: 480); transform.rotate(byDegrees: -26); transform.concat()
let orbit = NSBezierPath(ovalIn: NSRect(x: -325, y: -122, width: 650, height: 244))
mint.withAlphaComponent(0.55).setStroke(); orbit.lineWidth = 19; orbit.stroke()
NSGraphicsContext.restoreGraphicsState()
let pin = NSBezierPath()
pin.move(to: NSPoint(x: 512, y: 250))
pin.curve(to: NSPoint(x: 314, y: 594), controlPoint1: NSPoint(x: 460, y: 325), controlPoint2: NSPoint(x: 314, y: 465))
pin.curve(to: NSPoint(x: 710, y: 594), controlPoint1: NSPoint(x: 314, y: 857), controlPoint2: NSPoint(x: 710, y: 857))
pin.curve(to: NSPoint(x: 512, y: 250), controlPoint1: NSPoint(x: 710, y: 465), controlPoint2: NSPoint(x: 564, y: 325))
pin.close()
NSGradient(starting: NSColor.white, ending: mint)!.draw(in: pin, angle: -90)
NSColor(calibratedRed: 0.025, green: 0.22, blue: 0.32, alpha: 1).setFill()
NSBezierPath(ovalIn: NSRect(x: 401, y: 493, width: 222, height: 222)).fill()
let arrow = NSBezierPath()
arrow.move(to: NSPoint(x: 512, y: 687)); arrow.line(to: NSPoint(x: 455, y: 550))
arrow.line(to: NSPoint(x: 512, y: 579)); arrow.line(to: NSPoint(x: 569, y: 550)); arrow.close()
NSColor.white.setFill(); arrow.fill()
// A small satellite on the orbit signals GNSS rather than a general map app.
NSGraphicsContext.saveGraphicsState()
let satellite = NSAffineTransform(); satellite.translateX(by: 774, yBy: 687); satellite.rotate(byDegrees: -26); satellite.concat()
NSColor.white.setFill(); NSBezierPath(roundedRect: NSRect(x: -24, y: -31, width: 48, height: 62), xRadius: 9, yRadius: 9).fill()
mint.setFill()
for x in [-87.0, 38.0] { NSBezierPath(roundedRect: NSRect(x: x, y: -24, width: 49, height: 48), xRadius: 5, yRadius: 5).fill() }
NSGraphicsContext.restoreGraphicsState()
image.unlockFocus()
for size in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let pixels = size * scale
        let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels,
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
            bytesPerRow: 0, bitsPerPixel: 0)!
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
        NSGraphicsContext.current?.imageInterpolation = .high
        image.draw(in: NSRect(x: 0, y: 0, width: pixels, height: pixels))
        NSGraphicsContext.restoreGraphicsState()
        let png = bitmap.representation(using: .png, properties: [:])!
        let suffix = scale == 2 ? "@2x" : ""
        try png.write(to: destination.appendingPathComponent("icon_\(size)x\(size)\(suffix).png"))
        if pixels == 1024 { try png.write(to: preview) }
    }
}
