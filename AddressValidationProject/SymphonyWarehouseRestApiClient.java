package sneakpeeq.core.FulfillmentClient;

import sneakpeeq.core.entity.FAS.client.WarehouseClient.CreateShipmentRequest;
import sneakpeeq.core.entity.FAS.client.WarehouseClient.GetInventoryResponse;
import sneakpeeq.core.entity.FAS.client.WarehouseClient.AsnStatusResponse;
import sneakpeeq.core.entity.FAS.client.WarehouseClient.CreateAsnRequest;
import sneakpeeq.core.entity.FAS.client.WarehouseClient.CreateAsnResponse;
import sneakpeeq.core.entity.FAS.client.WarehouseClient.CreateShipmentResponse;
import sneakpeeq.core.entity.FAS.client.WarehouseClient.ShipmentStatusRequest;
import sneakpeeq.core.entity.FAS.client.WarehouseClient.ShipmentStatusResponse;
import sneakpeeq.core.entity.FAS.client.WarehouseClient.CancelShipmentResponse;
import sneakpeeq.core.exceptions.FAS.WarehouseException;
import sneakpeeq.core.exceptions.FAS.WarehouseNetworkException;
import sneakpeeq.core.exceptions.RequestTypeNotImplementedException;
import sneakpeeq.core.models.WarehouseApiInfo;
import sneakpeeq.core.util.http.HttpClientWrapper;
import sneakpeeq.core.util.HttpEndPointBuilder;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.codehaus.jackson.map.ObjectMapper;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import static org.eclipse.jetty.http.HttpMethod.POST;
import static org.eclipse.jetty.http.HttpMethod.GET;
import static org.eclipse.jetty.http.HttpMethod.DELETE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.easypost.model.EventData;
import com.stripe.model.EventDataDeserializer;
import com.google.common.collect.Maps;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import static java.lang.String.format;

@Service
public class SymphonyWarehouseRestApiClient {
	private static final Logger logger = LoggerFactory.getLogger(SymphonyWarehouseRestApiClient.class);
	private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
	@Autowired HttpClientWrapper httpClientWrapper;
	public static final Gson prettyPrintGson = new GsonBuilder().
			setPrettyPrinting().
			serializeNulls().
			setFieldNamingPolicy(FieldNamingPolicy.IDENTITY).
			registerTypeAdapter(EventData.class, new EventDataDeserializer()).create();

	// should not throw
	public CreateShipmentResponse postShipment(CreateShipmentRequest createShipmentRequest, WarehouseApiInfo apiInfo,
	                                           String clientRef){
		String endpoint = new HttpEndPointBuilder()
				.baseUrl(apiInfo.getUrl())
				.endPoint("shipments")
				.build();
		try{
			return doApiPost(CreateShipmentResponse.class, createShipmentRequest,
					endpoint, apiInfo, clientRef);
		} catch (WarehouseNetworkException e) {
			logger.error(e.getMessage());
			return new CreateShipmentResponse(e.getMessage());
		}
	}

	public ShipmentStatusResponse getShipmentStatuses(ShipmentStatusRequest shipmentStatusRequest,
	                                                  WarehouseApiInfo apiInfo, String clientRef){
		String endpoint = new HttpEndPointBuilder()
				.baseUrl(apiInfo.getUrl())
				.endPoint("shipments/fetch-status")
				.build();
		try{
			return doApiPost(ShipmentStatusResponse.class, shipmentStatusRequest, endpoint, apiInfo, clientRef);
		} catch (WarehouseNetworkException e) {
			logger.error(e.getMessage());
			return new ShipmentStatusResponse(e.getMessage());
		}
	}

	public CancelShipmentResponse cancelShipment(String shipmentId, WarehouseApiInfo apiInfo, String clientRef) {
		String endpoint = new HttpEndPointBuilder()
				.baseUrl(apiInfo.getUrl())
				.endPoint(format("shipments/%s", shipmentId))
				.build();
		try {
			return doApiDelete(CancelShipmentResponse.class, endpoint, apiInfo, clientRef);
		} catch (WarehouseNetworkException e) {
			logger.error(e.getMessage());
			return new CancelShipmentResponse(e.getMessage());
		}
	}

	public GetInventoryResponse getInventory(String sku, String warehouseCode, WarehouseApiInfo info, String clientRef) {
		HttpEndPointBuilder endpointBuilder = new HttpEndPointBuilder()
				.baseUrl(info.getUrl())
				.endPoint("inventories");
		if( sku != null ) {
			endpointBuilder.paramsKeyValue("sku", sku);
		}
		if( warehouseCode != null ) {
			endpointBuilder.paramsKeyValue("warehouse", warehouseCode);
		}
		String endpoint = endpointBuilder.build();
		try{
			return doApiGet(GetInventoryResponse.class, endpoint, info, clientRef);
		} catch (WarehouseNetworkException e) {
			logger.error(e.getMessage());
			return new GetInventoryResponse(e.getMessage());
		}
	}

	public CreateAsnResponse createAsn(CreateAsnRequest createAsnRequest, WarehouseApiInfo apiInfo, String clientRef){
		String endpoint = new HttpEndPointBuilder()
				.baseUrl(apiInfo.getUrl())
				.endPoint("asns")
				.build();
		try{
			return doApiPost(CreateAsnResponse.class, createAsnRequest,
					endpoint, apiInfo, clientRef);
		} catch (WarehouseNetworkException e) {
			logger.error(e.getMessage());
			return new CreateAsnResponse(e.getMessage());
		}
	}

	public AsnStatusResponse getAsnStatus(String symphonyAsnId, WarehouseApiInfo info, String clientRef) {
		HttpEndPointBuilder endpointBuilder = new HttpEndPointBuilder()
				.baseUrl(info.getUrl())
				.endPoint("asns")
				.paramsKeyValue("symphonyAsnId", symphonyAsnId);
		String endpoint = endpointBuilder.build();

		try{
			return doApiGet(AsnStatusResponse.class, endpoint, info, clientRef);
		} catch (WarehouseNetworkException e) {
			logger.error(e.getMessage());
			return new AsnStatusResponse(e.getMessage());
		}
	}

	private <T> T doApiRequest(Class apiResponseClass, String endpoint, WarehouseApiInfo apiInfo, String clientRef, HttpMethod method) {
		return doApiRequest(apiResponseClass, endpoint, apiInfo, clientRef, method, "");
	}

	private <T> T doApiRequest(Class apiResponseClass, String endpoint, WarehouseApiInfo apiInfo, String clientRef, HttpMethod method, String requestJson) {
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.setDateFormat(dateFormat);
		String username = apiInfo.getUsername();
		String password = apiInfo.getPassword();
		Map<String, String> customHeaders = Maps.newHashMap();
		customHeaders.put("WarehouseKey", apiInfo.getWarehouseKey());
		customHeaders.put("ClientRef", clientRef);

		try {
			Future<ContentResponse> response;
			switch(method) {
				case POST:
					response = httpClientWrapper.postJSON(endpoint, requestJson, username, password, customHeaders);
					break;
				case DELETE:
					response = httpClientWrapper.delete(endpoint, username, password, customHeaders);
					break;
				case GET:
					response = httpClientWrapper.getJSON(endpoint, username, password, customHeaders);
					break;
				default:
					throw new RequestTypeNotImplementedException("HTTP {} Type Not Implemented");
			}
			String respString = response.get().getContentAsString();
			T responseObj = (T) objectMapper.readValue(respString, apiResponseClass);
			logger.info("{}: {} with username: {} \n" +
					"response {}", method, endpoint, username, prettyPrintGson.toJson(responseObj));
			return responseObj;
		} catch (IOException | InterruptedException | ExecutionException e) {
			throw new WarehouseNetworkException(format("HTTP {} Error: endpoint : %s, username : %s, ",
					method, endpoint, username));
		}
	}

	private <T> T doApiPost(Class apiResponseClass, Object request, String endpoint, WarehouseApiInfo apiInfo,
	                        String clientRef){
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.setDateFormat(dateFormat);
		String requestJson;
		try {
			requestJson = objectMapper.writeValueAsString(request);
		} catch (IOException e) {
			throw new WarehouseException(format("Failed to map request to json. Request : %s",request));
		}
		return doApiRequest(apiResponseClass, endpoint, apiInfo, clientRef, POST, requestJson);
	}

	private <T> T doApiDelete(Class apiResponseClass, String endpoint, WarehouseApiInfo apiInfo, String clientRef) {
		return doApiRequest(apiResponseClass, endpoint, apiInfo, clientRef, DELETE);
	}

	private <T> T doApiGet(Class apiResponseClass, String endpoint, WarehouseApiInfo apiInfo, String clientRef){
		return doApiRequest(apiResponseClass, endpoint, apiInfo, clientRef,  GET);
	}
}
