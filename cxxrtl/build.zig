const std = @import("std");

pub fn build(b: *std.Build) void {
    const yosys_data_dir = b.option([]const u8, "yosys_data_dir", "yosys data dir (per yosys-config --datdir)") orelse std.debug.panic("requires -Dyosys_data_dir=<...>", .{});
    const cxxrtl_o_paths = b.option([]const u8, "cxxrtl_o_paths", "comma-separated paths to .o files to link against, including CXXRTL simulation") orelse std.debug.panic("requires -Dcxxrtl_o_paths=<...>,<...>", .{});

    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const exe = b.addExecutable(.{
        .name = "cxxrtl",
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimize,
    });
    exe.linkLibCpp();

    var it = std.mem.split(u8, cxxrtl_o_paths, ",");
    while (it.next()) |cxxrtl_o_path| {
        exe.addObjectFile(b.path(cxxrtl_o_path));
    }

    exe.addIncludePath(.{ .cwd_relative = b.fmt("{s}/include/backends/cxxrtl/runtime", .{yosys_data_dir}) });

    b.installArtifact(exe);

    const run_cmd = b.addRunArtifact(exe);
    run_cmd.step.dependOn(b.getInstallStep());
    if (b.args) |args| {
        run_cmd.addArgs(args);
    }

    const run_step = b.step("run", "Run the app");
    run_step.dependOn(&run_cmd.step);
}
