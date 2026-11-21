package com.bunny.dispatch.service.dispatch;

import com.bunny.dispatch.dto.request.OrdersBatchRequest;
import com.bunny.dispatch.dto.request.VehiclesBatchRequest;
import com.bunny.dispatch.dto.response.AckResponse;
import com.bunny.dispatch.dto.response.ClearDataResponse;
import com.bunny.dispatch.dto.response.DispatchPlanResponse;

public interface DispatchService {

	AckResponse ingestOrders(OrdersBatchRequest batch);

	AckResponse ingestVehicles(VehiclesBatchRequest batch);

	DispatchPlanResponse buildPlan();

	ClearDataResponse clearData();

}
