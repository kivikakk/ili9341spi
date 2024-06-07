const std = @import("std");

const c = @cImport({
    @cInclude("cxxrtl/capi/cxxrtl_capi.h");
    @cInclude("cxxrtl/capi/cxxrtl_capi_vcd.h");
});

extern "c" fn cxxrtl_design_create() c.cxxrtl_toplevel;

pub fn main() !void {
    std.debug.print("All your {s} are belong to us.\n", .{"codebase"});

    const handle = c.cxxrtl_create(cxxrtl_design_create());

    c.cxxrtl_destroy(handle);
}
