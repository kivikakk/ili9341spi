const std = @import("std");
const SDL = @import("sdl2");

const Args = @import("./Args.zig");
const SimController = @import("./SimController.zig");
const SimThread = @import("./SimThread.zig");

pub fn main() !void {
    var gpa = std.heap.GeneralPurposeAllocator(.{}){};
    defer _ = gpa.deinit();
    const alloc = gpa.allocator();

    var args = try Args.parse(alloc);
    defer args.deinit();

    var sim_controller = try SimController.start(alloc, args.vcd_out);
    defer sim_controller.joinDeinit();

    try SDL.init(.{ .video = true, .events = true });
    defer SDL.quit();

    var window = try SDL.createWindow("ili9341spi", .default, .default, 640, 480, .{});
    defer window.destroy();

    // XXX Turning off vsync seems to increase the simthread speed too (and
    // render at 5-10x the rate ofc). Is the sync happening while we poll events
    // or something?
    //
    // vsync isn't even exactly "well-defined" given adaptive refresh rates;
    // moving the mouse cursor increases it up to my refresh rate, reminscent of
    // Windows 95-era async I/O.
    var renderer = try SDL.createRenderer(window, null, .{
        .accelerated = true,
        .present_vsync = true,
    });
    defer renderer.destroy();

    var itexture = try SDL.createTexture(renderer, .bgr565, .streaming, SimThread.WIDTH, SimThread.HEIGHT);
    defer itexture.destroy();

    var ticks_last = SDL.getTicks64();
    var cycles_last: usize = 0;
    var frame_count: usize = 0;

    var img_data: SimThread.ImgData = undefined;

    while (sim_controller.lockIfRunning()) {
        const cycles_now = sim_controller.cycleNumber();
        var updated_img_data = false;

        {
            defer sim_controller.unlock();

            while (SDL.pollEvent()) |ev| {
                switch (ev) {
                    .quit => sim_controller.halt(),
                    .key_down => |key| {
                        if (key.keycode == .escape) sim_controller.halt();
                    },
                    else => {},
                }
            }

            updated_img_data = sim_controller.maybeUpdateImgData(&img_data);
        }

        if (updated_img_data) {
            var pix = try itexture.lock(null);
            defer pix.release();

            std.debug.assert(pix.stride == SimThread.WIDTH * 2);
            @memcpy(@as(*SimThread.ImgData, @ptrCast(@alignCast(pix.pixels))), &img_data);
        }

        try renderer.clear();

        try renderer.copy(itexture, null, null);

        renderer.present();
        frame_count += 1;

        const ticks_now = SDL.getTicks64();
        if (ticks_now - ticks_last > 1000) {
            const ticks_elapsed = ticks_now - ticks_last;
            const cycles_elapsed = cycles_now - cycles_last;
            const fps: u64 = @intFromFloat(@as(f32, @floatFromInt(frame_count)) / (@as(f32, @floatFromInt(ticks_elapsed)) / 1000.0));

            var buffer = [_]u8{undefined} ** 60;
            const title = try std.fmt.bufPrintZ(&buffer, "ili9341spi - {d}{s} / {d} fps", .{
                if (cycles_elapsed < 1_000)
                    cycles_elapsed
                else if (cycles_elapsed < 1_000_000)
                    cycles_elapsed / 1_000
                else
                    cycles_elapsed / 1_000_000,
                if (cycles_elapsed < 1_000)
                    "Hz"
                else if (cycles_elapsed < 1_000_000)
                    "kHz"
                else
                    "MHz",
                fps,
            });
            window.setTitle(title);

            ticks_last = ticks_now;
            cycles_last = cycles_now;
            frame_count = 0;
        }
    }
}
