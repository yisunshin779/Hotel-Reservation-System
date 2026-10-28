package com.Hotel.Module_CheckInCheckOut.entity;

public record Receptionist(long id, long branchId, String role) {
    public void authorize(long targetBranch) {
        if (!"RECEPTIONIST".equals(role) || branchId != targetBranch)
            throw new StayException(403, "FORBIDDEN", "Chỉ lễ tân của chi nhánh được thực hiện thao tác này.");
    }
}
