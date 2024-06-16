const std = @import("std");
const SDL = @import("SDL.zig");
const zxxrtl = @import("zxxrtl");

pub fn build(b: *std.Build) void {
    const yosys_data_dir = b.option([]const u8, "yosys_data_dir", "yosys data dir (per yosys-config --datdir)") orelse guessYosysDataDir(b);
    const cxxrtl_o_paths = b.option([]const u8, "cxxrtl_o_paths", "comma-separated paths to .o files to link against, including CXXRTL simulation") orelse
        "../build/cxxrtl/ili9341spi.o";
    const clock_hz = b.option(usize, "clock_hz", "clock speed the gateware is elaborated at in Hz") orelse 1_000_000;

    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const sdlsdk = SDL.init(b, null);

    const exe = b.addExecutable(.{
        .name = "cxxrtl",
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimize,
    });
    exe.linkLibCpp();
    sdlsdk.link(exe, .dynamic);
    exe.root_module.addImport("sdl2", sdlsdk.getWrapperModule());

    const zxxrtl_mod = b.dependency("zxxrtl", .{ .target = target, .optimize = optimize }).module("zxxrtl");
    zxxrtl_mod.addIncludePath(.{ .cwd_relative = b.fmt("{s}/include/backends/cxxrtl/runtime", .{yosys_data_dir}) });
    exe.root_module.addImport("zxxrtl", zxxrtl_mod);

    var it = std.mem.split(u8, cxxrtl_o_paths, ",");
    while (it.next()) |cxxrtl_o_path| {
        exe.addObjectFile(b.path(cxxrtl_o_path));
    }

    const options = b.addOptions();
    options.addOption(usize, "clock_hz", clock_hz);
    exe.root_module.addOptions("options", options);

    b.installArtifact(exe);

    const run_cmd = b.addRunArtifact(exe);
    run_cmd.step.dependOn(b.getInstallStep());
    if (b.args) |args| {
        run_cmd.addArgs(args);
    }

    const run_step = b.step("run", "Run the app");
    run_step.dependOn(&run_cmd.step);
}

fn guessYosysDataDir(b: *std.Build) []const u8 {
    const result = std.process.Child.run(.{
        .allocator = b.allocator,
        .argv = &.{ "yosys-config", "--datdir" },
        .expand_arg0 = .expand,
    }) catch @panic("couldn't run yosys-config; please supply -Dyosys_data_dir");
    return std.mem.trim(u8, result.stdout, "\n");
}
