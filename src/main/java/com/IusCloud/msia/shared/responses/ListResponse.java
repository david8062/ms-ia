package com.IusCloud.msia.shared.responses;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ListResponse<T> {
    private LocalDateTime timestamp = LocalDateTime.now();
    private int status = 200;
    private String message = "Operación exitosa";
    private int count;
    private List<T> data;

    public ListResponse(List<T> data) {
        this.data = data;
        this.count = data != null ? data.size() : 0;
    }
}
