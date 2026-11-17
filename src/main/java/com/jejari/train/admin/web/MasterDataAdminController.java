package com.jejari.train.admin.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.train.admin.MasterDataService;
import com.jejari.train.admin.MasterDataService.CarType;
import com.jejari.train.admin.MasterDataService.CarTypeCommand;
import com.jejari.train.admin.MasterDataService.Route;
import com.jejari.train.admin.MasterDataService.RouteCommand;
import com.jejari.train.admin.MasterDataService.Station;
import com.jejari.train.admin.MasterDataService.Train;
import com.jejari.train.admin.MasterDataService.TrainCommand;

/**
 * ADM-01 기준 데이터 관리 API (화면 A-02, 기능설계서 7장 {@code /admin/stations|routes|car-types|trains}).
 *
 * <p>보기는 VIEWER, 저장은 설계서대로 SUPERVISOR. 화면은 SUPERVISOR가 아니면 저장 버튼을 끈다 (A-02 오류 표).
 * 역·노선·차량 유형은 지우지 않는 참조 데이터라 DELETE가 없다.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')")
public class MasterDataAdminController {

	private static final String SUPERVISOR = "hasAuthority('ADMIN_SUPERVISOR')";

	private final MasterDataService master;

	public MasterDataAdminController(MasterDataService master) {
		this.master = master;
	}

	@GetMapping("/stations")
	public List<Station> stations(@RequestParam(required = false) String q) {
		return master.stations(q);
	}

	@PostMapping("/stations")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(SUPERVISOR)
	public Station createStation(@RequestBody Station body) {
		return master.createStation(body);
	}

	@PutMapping("/stations/{stationId}")
	@PreAuthorize(SUPERVISOR)
	public Station updateStation(@PathVariable String stationId, @RequestBody Station body) {
		return master.updateStation(stationId, body);
	}

	@GetMapping("/routes")
	public List<Route> routes() {
		return master.routes();
	}

	@PostMapping("/routes")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(SUPERVISOR)
	public Route createRoute(@RequestBody RouteCommand body) {
		return master.createRoute(body);
	}

	/** stations를 빼고 보내면 이름만 바꾼다 */
	@PutMapping("/routes/{routeId}")
	@PreAuthorize(SUPERVISOR)
	public Route updateRoute(@PathVariable long routeId, @RequestBody RouteCommand body) {
		return master.updateRoute(routeId, body);
	}

	@GetMapping("/car-types")
	public List<CarType> carTypes() {
		return master.carTypes();
	}

	@PostMapping("/car-types")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(SUPERVISOR)
	public CarType createCarType(@RequestBody CarTypeCommand body) {
		return master.createCarType(body);
	}

	@PutMapping("/car-types/{code}")
	@PreAuthorize(SUPERVISOR)
	public CarType updateCarType(@PathVariable String code, @RequestBody CarTypeCommand body) {
		return master.updateCarType(code, body);
	}

	@GetMapping("/trains")
	public List<Train> trains() {
		return master.trains();
	}

	@GetMapping("/trains/{trainId}")
	public Train train(@PathVariable long trainId) {
		return master.train(trainId);
	}

	/** 저장하면 호차 구성대로 좌석이 함께 생긴다 */
	@PostMapping("/trains")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(SUPERVISOR)
	public Train createTrain(@RequestBody TrainCommand body) {
		return master.createTrain(body);
	}

	@PutMapping("/trains/{trainId}")
	@PreAuthorize(SUPERVISOR)
	public Train updateTrain(@PathVariable long trainId, @RequestBody TrainCommand body) {
		return master.updateTrain(trainId, body);
	}
}
